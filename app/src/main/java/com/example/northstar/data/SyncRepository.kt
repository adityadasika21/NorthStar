package com.example.northstar.data

import android.content.Context
import com.example.northstar.util.Dbg
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single store the Garage + saved-location ViewModels talk to. Local SQLite is the
 * source of truth; every mutation is mirrored to Firestore under users/{uid}/… and
 * live snapshot listeners apply remote changes back into SQLite — so data syncs across
 * the rider's devices (and works offline via Firestore's local cache).
 *
 * Sync model (single user, last-write-wins): each row has a stable [sid] = its Firestore
 * doc id, so the same record maps 1:1 everywhere. On [startSync] each collection is
 * uploaded once IF the cloud copy is empty (lets an existing device seed the cloud),
 * then kept live. Conflicts are rare for one rider and resolve last-write-wins.
 */
class SyncRepository private constructor(context: Context) {
    companion object {
        private const val TAG = "SyncRepository"
        @Volatile private var instance: SyncRepository? = null
        fun get(context: Context): SyncRepository =
            instance ?: synchronized(this) { instance ?: SyncRepository(context.applicationContext).also { instance = it } }
    }

    private val db = NorthstarDb.get(context)
    // Firebase is optional (bring-your-own-project). When no google-services.json was
    // bundled, these stay null and every mirror/listen call is a no-op — the app runs
    // fully local. See [FirebaseGate].
    private val firebaseOn = FirebaseGate.isConfigured(context)
    private val fs: FirebaseFirestore? = if (firebaseOn) FirebaseFirestore.getInstance() else null
    private val auth: FirebaseAuth? = if (firebaseOn) FirebaseAuth.getInstance() else null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Bumped on every local OR remote data change so ViewModels reload. */
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    private fun bump() { _revision.value++ }

    private val regs = mutableListOf<ListenerRegistration>()

    init {
        // Heal any duplicate seeded maintenance items left by older upgrade paths, as soon as the
        // store is created — even before sign-in, so the Garage is clean offline too.
        io.launch { reconcileMaintenanceDuplicates() }
    }

    private fun userDoc(): DocumentReference? =
        auth?.currentUser?.uid?.let { fs?.collection("users")?.document(it) }

    /**
     * Collapse duplicate seeded maintenance items locally and, when signed in, remove the stale
     * Firestore docs + re-push the survivors under their canonical sids so the cloud copy can't
     * re-sync the duplicates back. No-op once everything is canonical (so it won't churn).
     */
    private fun reconcileMaintenanceDuplicates() {
        val (removed, changed) = db.dedupeMaintenance()
        if (!changed) return
        android.util.Log.i(TAG, "maintenance dedupe: removed ${removed.size} stale row(s), realigned names/sids")
        val mc = userDoc()?.collection("maintenance")
        if (mc != null) {
            // Drop the stale docs, then re-push EVERY survivor so a name/sid realignment (not just a
            // deletion) reaches the cloud — otherwise the next snapshot would pull the old name back.
            removed.forEach { mc.document(it).delete() }
            db.maintenanceItems().forEach { pushMaintenance(it) }
        }
        bump()
    }

    // ── Reads (local) ────────────────────────────────────────────────────
    fun odometer() = db.odometer()
    fun fuelFills() = db.fuelFills()
    fun maintenanceItems() = db.maintenanceItems()
    fun savedLocations() = db.savedLocations()
    fun rides() = db.rides()

    // ── Mutations (local write-through + Firestore mirror) ────────────────
    fun setOdometer(km: Double) { db.setOdometer(km); pushOdometer(km); bump() }

    fun addFuel(litres: Double, cost: Double, odoKm: Int, location: String) {
        val prevOdo = db.odometer()
        val f = FuelFillup(sid = NorthstarDb.newSid(), dateMs = System.currentTimeMillis(),
            litres = litres, cost = cost, odometerKm = odoKm, location = location)
        db.upsertFuel(f); pushFuel(f)
        if (odoKm > prevOdo) { db.setOdometer(odoKm.toDouble()); pushOdometer(odoKm.toDouble()) }
        bump()
    }
    fun deleteFuel(f: FuelFillup) { db.deleteFuelBySid(f.sid); userDoc()?.collection("fuel")?.document(f.sid)?.delete(); bump() }

    fun addMaintenance(name: String, icon: String, intervalKm: Int, lastDoneOdoKm: Int, intervalMonths: Int = 0) {
        val m = MaintenanceItem(sid = NorthstarDb.newSid(), name = name, iconKey = icon,
            intervalKm = intervalKm, lastDoneOdoKm = lastDoneOdoKm, lastDoneDateMs = System.currentTimeMillis(),
            intervalMonths = intervalMonths)
        db.upsertMaintenance(m); pushMaintenance(m); bump()
    }
    /**
     * Log a service VISIT: a single event that resets the countdown on every covered
     * maintenance item, links to a scheduled milestone if [scheduledKey] is set, and records the
     * cost + uploaded invoice. The record's METADATA syncs (so the service history survives a
     * reinstall / restores on a second device); the invoice FILE stays on-device, so a restored
     * record just has no invoice attached. Scheduled services / documents remain local-only.
     */
    fun logVisit(
        title: String, kind: String, scheduledKey: String, items: List<MaintenanceItem>,
        odoKm: Int, cost: Double, invoicePath: String, note: String,
    ) {
        val now = System.currentTimeMillis()
        items.forEach { m ->
            val u = m.copy(lastDoneOdoKm = odoKm, lastDoneDateMs = now)
            db.upsertMaintenance(u); pushMaintenance(u)
        }
        val rec = ServiceRecord(
            sid = NorthstarDb.newSid(), title = title, kind = kind, scheduledKey = scheduledKey,
            itemSids = items.map { it.sid }, odometerKm = odoKm, dateMs = now, cost = cost,
            invoicePath = invoicePath, note = note)
        db.upsertServiceRecord(rec); pushServiceRecord(rec)
        bump()
    }

    fun serviceRecords() = db.serviceRecords()
    fun deleteServiceRecord(r: ServiceRecord) {
        db.deleteServiceRecordBySid(r.sid)
        userDoc()?.collection("services")?.document(r.sid)?.delete()
        if (r.invoicePath.isNotBlank()) runCatching { java.io.File(r.invoicePath).delete() }
        bump()
    }

    // ── Scheduled services ───────────────────────────────────────────────────
    fun scheduledServices() = db.scheduledServices()
    fun addScheduledService(label: String, targetKm: Int, targetMonths: Int) {
        db.upsertScheduledService(ScheduledService(
            sid = NorthstarDb.newSid(), label = label, targetKm = targetKm,
            targetMonths = targetMonths, free = false, orderIdx = 100))
        bump()
    }
    fun deleteScheduledService(s: ScheduledService) { db.deleteScheduledServiceBySid(s.sid); bump() }

    // ── Documents (Glovebox) ─────────────────────────────────────────────────
    fun documents() = db.documents()
    fun upsertDocument(d: VehicleDocument) { db.upsertDocument(d); bump() }
    fun deleteDocument(d: VehicleDocument) {
        db.deleteDocumentBySid(d.sid)
        if (d.filePath.isNotBlank()) runCatching { java.io.File(d.filePath).delete() }
        bump()
    }

    // ── Bike identity ─────────────────────────────────────────────────────────
    fun bikeIdentity() = db.bikeIdentity()
    fun setBikeIdentity(b: BikeIdentity) { db.setBikeIdentity(b); bump() }
    fun deleteMaintenance(m: MaintenanceItem) { db.deleteMaintenanceBySid(m.sid); userDoc()?.collection("maintenance")?.document(m.sid)?.delete(); bump() }

    fun addSaved(name: String, lat: Double, lng: Double, note: String) {
        val s = SavedLocation(sid = NorthstarDb.newSid(), name = name, lat = lat, lng = lng, note = note)
        db.upsertSaved(s); pushSaved(s); bump()
    }
    fun renameSaved(s: SavedLocation, name: String, note: String) {
        val u = s.copy(name = name, note = note); db.upsertSaved(u); pushSaved(u); bump()
    }
    fun deleteSaved(s: SavedLocation) { db.deleteSavedBySid(s.sid); userDoc()?.collection("saved")?.document(s.sid)?.delete(); bump() }

    /**
     * Persist a finished ride (local + cloud). Self-scopes on the repo's own IO scope so the
     * write survives even when called from ViewModel teardown (onCleared cancels viewModelScope
     * before a launch there could run — that was silently dropping rides).
     */
    fun addRide(r: Ride) { io.launch {
        db.upsertRide(r); pushRide(r)
        // A completed ride advances the bike's odometer by exactly what was ridden.
        db.addToOdometer(r.distanceKm)
        val newOdo = db.odometer(); pushOdometer(newOdo)
        bump()
    } }
    fun deleteRide(r: Ride) { db.deleteRideBySid(r.sid); userDoc()?.collection("rides")?.document(r.sid)?.delete(); bump() }

    // ── Firestore push helpers ───────────────────────────────────────────
    private fun pushOdometer(km: Double) { userDoc()?.collection("state")?.document("bike")?.set(mapOf("odometerKm" to km)) }
    private fun pushFuel(f: FuelFillup) {
        userDoc()?.collection("fuel")?.document(f.sid)?.set(
            mapOf("dateMs" to f.dateMs, "litres" to f.litres, "cost" to f.cost, "odometerKm" to f.odometerKm, "location" to f.location))
    }
    private fun pushMaintenance(m: MaintenanceItem) {
        userDoc()?.collection("maintenance")?.document(m.sid)?.set(
            mapOf("name" to m.name, "iconKey" to m.iconKey, "intervalKm" to m.intervalKm,
                "lastDoneOdoKm" to m.lastDoneOdoKm, "lastDoneDateMs" to m.lastDoneDateMs,
                "intervalMonths" to m.intervalMonths))
    }
    private fun pushServiceRecord(r: ServiceRecord) {
        // Metadata only — the invoice file is device-local and isn't uploaded.
        userDoc()?.collection("services")?.document(r.sid)?.set(
            mapOf("title" to r.title, "kind" to r.kind, "scheduledKey" to r.scheduledKey,
                "itemSids" to r.itemSids, "odometerKm" to r.odometerKm, "dateMs" to r.dateMs,
                "cost" to r.cost, "note" to r.note))
    }
    private fun pushSaved(s: SavedLocation) {
        userDoc()?.collection("saved")?.document(s.sid)?.set(
            mapOf("name" to s.name, "lat" to s.lat, "lng" to s.lng, "note" to s.note, "createdMs" to s.createdMs))
    }
    private fun pushRide(r: Ride) {
        userDoc()?.collection("rides")?.document(r.sid)?.set(
            mapOf("startMs" to r.startMs, "endMs" to r.endMs, "distanceMeters" to r.distanceMeters,
                "durationSec" to r.durationSec, "avgSpeedMps" to r.avgSpeedMps, "maxSpeedMps" to r.maxSpeedMps,
                "track" to r.trackPolyline, "startLat" to r.startLat, "startLng" to r.startLng,
                "endLat" to r.endLat, "endLng" to r.endLng))
    }

    // ── Sync lifecycle ───────────────────────────────────────────────────
    fun startSync() {
        val u = userDoc() ?: return
        stopSync()
        Dbg.i(TAG) { "startSync for uid=${auth?.currentUser?.uid}" }

        listen(u.collection("fuel"),
            uploadLocal = { db.fuelFills().forEach { pushFuel(it) } },
            apply = { doc -> db.upsertFuel(FuelFillup(
                sid = doc.id, dateMs = doc.getLong("dateMs") ?: 0L, litres = doc.getDouble("litres") ?: 0.0,
                cost = doc.getDouble("cost") ?: 0.0, odometerKm = (doc.getLong("odometerKm") ?: 0L).toInt(),
                location = doc.getString("location") ?: "")) },
            remove = { db.deleteFuelBySid(it) })

        listen(u.collection("maintenance"),
            uploadLocal = { db.maintenanceItems().forEach { pushMaintenance(it) } },
            apply = { doc -> db.upsertMaintenance(MaintenanceItem(
                sid = doc.id, name = doc.getString("name") ?: "", iconKey = doc.getString("iconKey") ?: "wrench",
                intervalKm = (doc.getLong("intervalKm") ?: 0L).toInt(), lastDoneOdoKm = (doc.getLong("lastDoneOdoKm") ?: 0L).toInt(),
                lastDoneDateMs = doc.getLong("lastDoneDateMs") ?: 0L,
                intervalMonths = (doc.getLong("intervalMonths") ?: 0L).toInt())) },
            remove = { db.deleteMaintenanceBySid(it) },
            // A remote pull can re-introduce a duplicate (old random-sid doc alongside the seed
            // sid) — collapse it again and clean the cloud so it converges instead of ping-ponging.
            afterApply = { reconcileMaintenanceDuplicates() })

        listen(u.collection("saved"),
            uploadLocal = { db.savedLocations().forEach { pushSaved(it) } },
            apply = { doc -> db.upsertSaved(SavedLocation(
                sid = doc.id, name = doc.getString("name") ?: "", lat = doc.getDouble("lat") ?: 0.0,
                lng = doc.getDouble("lng") ?: 0.0, note = doc.getString("note") ?: "",
                createdMs = doc.getLong("createdMs") ?: System.currentTimeMillis())) },
            remove = { db.deleteSavedBySid(it) })

        listen(u.collection("rides"),
            uploadLocal = { db.rides().forEach { pushRide(it) } },
            apply = { doc -> db.upsertRide(Ride(
                sid = doc.id, startMs = doc.getLong("startMs") ?: 0L, endMs = doc.getLong("endMs") ?: 0L,
                distanceMeters = doc.getDouble("distanceMeters") ?: 0.0, durationSec = doc.getLong("durationSec") ?: 0L,
                avgSpeedMps = doc.getDouble("avgSpeedMps") ?: 0.0, maxSpeedMps = doc.getDouble("maxSpeedMps") ?: 0.0,
                trackPolyline = doc.getString("track") ?: "",
                startLat = doc.getDouble("startLat") ?: 0.0, startLng = doc.getDouble("startLng") ?: 0.0,
                endLat = doc.getDouble("endLat") ?: 0.0, endLng = doc.getDouble("endLng") ?: 0.0)) },
            remove = { db.deleteRideBySid(it) })

        listen(u.collection("services"),
            uploadLocal = { db.serviceRecords().forEach { pushServiceRecord(it) } },
            apply = { doc ->
                @Suppress("UNCHECKED_CAST")
                val itemSids = (doc.get("itemSids") as? List<String>) ?: emptyList()
                // Keep a local invoice file if this device already has the record; a freshly
                // restored record (other device / reinstall) simply has no invoice attached.
                val localInvoice = db.serviceRecords().firstOrNull { it.sid == doc.id }?.invoicePath ?: ""
                db.upsertServiceRecord(ServiceRecord(
                    sid = doc.id, title = doc.getString("title") ?: "",
                    kind = doc.getString("kind") ?: "company", scheduledKey = doc.getString("scheduledKey") ?: "",
                    itemSids = itemSids, odometerKm = (doc.getLong("odometerKm") ?: 0L).toInt(),
                    dateMs = doc.getLong("dateMs") ?: 0L, cost = doc.getDouble("cost") ?: 0.0,
                    invoicePath = localInvoice, note = doc.getString("note") ?: "")) },
            remove = { db.deleteServiceRecordBySid(it) })

        // Odometer: single doc. Pull if present, else seed the cloud from local.
        regs += u.collection("state").document("bike").addSnapshotListener { snap, _ ->
            io.launch {
                val km = snap?.getDouble("odometerKm")
                if (km != null) { db.setOdometer(km); bump() } else pushOdometer(db.odometer())
            }
        }
    }

    fun stopSync() { regs.forEach { it.remove() }; regs.clear() }

    private fun listen(
        col: CollectionReference,
        uploadLocal: () -> Unit,
        apply: (DocumentSnapshot) -> Unit,
        remove: (String) -> Unit,
        afterApply: () -> Unit = {},
    ) {
        // One-time: if the cloud copy is empty, push our local rows up to seed it.
        col.get().addOnSuccessListener { qs -> if (qs.isEmpty) io.launch { uploadLocal() } }
        regs += col.addSnapshotListener { snap, err ->
            if (err != null || snap == null) return@addSnapshotListener
            io.launch {
                for (ch in snap.documentChanges) {
                    when (ch.type) {
                        DocumentChange.Type.ADDED, DocumentChange.Type.MODIFIED -> apply(ch.document)
                        DocumentChange.Type.REMOVED -> remove(ch.document.id)
                    }
                }
                if (snap.documentChanges.isNotEmpty()) { afterApply(); bump() }
            }
        }
    }
}
