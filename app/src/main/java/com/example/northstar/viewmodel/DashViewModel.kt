package com.example.northstar.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.dash.DashKeepAliveService
import com.example.northstar.dash.DashSession
import com.example.northstar.dash.DashState
import com.example.northstar.dash.DashWifiManager
import com.example.northstar.dash.WifiConnStatus
import com.example.northstar.dash.map.LocationTracker
import com.example.northstar.dash.map.MapRenderer
import com.example.northstar.dash.map.Mercator
import com.example.northstar.dash.map.TileProvider
import com.example.northstar.dash.nav.GeoPoint
import com.example.northstar.dash.nav.Route
import com.example.northstar.dash.nav.Router
import com.example.northstar.dash.protocol.DashCommands
import com.example.northstar.dash.video.DashEncoder
import com.example.northstar.dash.video.NalProcessor
import com.example.northstar.dash.video.RtpPacketizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ConnStage { OFFLINE, WIFI, AUTH, STREAMING, ERROR }

/**
 * GPS health shown to the rider so a bad signal degrades *visibly* instead of the marker just
 * freezing. GOOD = fresh, accurate. WEAK = fresh but imprecise (or fixes coming slowly) → the
 * map eases gently toward truth, no jitter. LOST = no fresh fix for a few seconds → marker holds
 * its last known spot and the dash says so.
 */
enum class GpsStatus { GOOD, WEAK, LOST }

data class DashUiState(
    val stage: ConnStage = ConnStage.OFFLINE,
    val frameCount: Int = 0,
    val lastButton: String? = null,
    val ssid: String = "",            // empty until a dash is discovered/paired (see DashConfig)
    val wifiPassword: String = "12345678",  // RE Tripper factory passphrase; rider-overridable
    val destinationName: String? = null,
    val errorMessage: String? = null,
    val retryAttempt: Int = 0,        // >0 while auto-retrying the flaky auth handshake
    val mapZoom: Int = 19,
    val remainingKm: Double? = null,
    val etaMinutes: Int? = null,
    val maneuver: String? = null,
    val hasGps: Boolean = false,
    val gpsStatus: GpsStatus = GpsStatus.LOST,
    val hasRoute: Boolean = false,
    val offRoute: Boolean = false,
    val recalculating: Boolean = false, // off-route long enough / actively rerouting → ETA frozen, pill shows "Recalculating"
    val headingUp: Boolean = true,
    val followMode: Boolean = true,
    val thermal: String = "OK",
    val needsWifiOn: Boolean = false,   // Wi‑Fi radio is off → UI prompts to enable it
    // For the in-app Google Map view
    val riderLat: Double? = null,
    val riderLng: Double? = null,
    val riderBearing: Float = 0f,
    val destLatLng: Pair<Double, Double>? = null,
    val routePoints: List<GeoPoint> = emptyList(),
)

class DashViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(DashUiState())
    val ui = _ui.asStateFlow()

    // The EXACT frame being H.264-encoded to the dash, published for the in-app "Dash view"
    // so the phone preview is pixel-identical to the bike screen (same renderer, not a second
    // map engine). A fresh immutable snapshot per redraw — the encoder's own bitmap is mutated
    // on a background thread and recycled on teardown, so handing that out would tear/crash.
    private val _previewFrame = MutableStateFlow<Bitmap?>(null)
    val previewFrame = _previewFrame.asStateFlow()

    private val session     = DashSession(viewModelScope)
    private val wifiManager = DashWifiManager(app, viewModelScope)
    private val dashConfig  = com.example.northstar.dash.DashConfig.get(app)
    private val voice        = com.example.northstar.dash.nav.VoiceManager.get(app)
    private val repo         = com.example.northstar.data.SyncRepository.get(app)
    private val recorder     = com.example.northstar.data.RideRecorder()
    private var recordJob: Job? = null
    private val tiles       = TileProvider(app, viewModelScope)
    private val location    = LocationTracker(app)
    private val mapRenderer = MapRenderer(tiles)
    private val powerManager = app.getSystemService(Application.POWER_SERVICE) as PowerManager

    private var encoder: DashEncoder? = null
    private var streamJob: Job? = null
    // Even presentation clock (ms) feeding the RTP 90 kHz timestamp — advances by exactly one
    // frame interval per encoded frame so the dash gets an evenly-spaced timeline (NOT wall clock,
    // which inherited the loop's jitter). Paired with absolute-grid send pacing below.
    @Volatile private var videoPtsMs = 0L
    private var sendPaceMs = 0.0    // EMA of the real frame-send interval — exposes cadence jitter
    private var sendPaceMaxMs = 0L  // worst interval since the last diag line
    // Backstop so a dropped/never-reached connection can't drain the battery forever: if we don't
    // reach STREAMING within this window, give up completely (stop the Wi‑Fi reconnect loop, the
    // foreground service, GPS and the encoder). Cancelled the moment streaming starts.
    private var giveupJob: Job? = null

    private var userWantsConnection = false
    private var authAttempts = 0       // bounded auto-retry of the (flaky) auth handshake

    // ── Navigation/map state read by the 4 fps frame loop ──
    @Volatile private var destLat: Double? = null
    @Volatile private var destLng: Double? = null
    @Volatile private var route: Route? = null
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    @Volatile private var zoom = 19          // nav-level zoom on the dash (street-level default)
    @Volatile private var headingUp = true
    @Volatile private var followMode = true
    @Volatile private var lastManualPanAt = 0L

    // Smoothed camera — eased toward the latest GPS target every frame so the map
    // glides at 8 fps instead of jumping once per 1 Hz fix (the "cheap"/laggy feel).
    private var camLat = 0.0
    private var camLng = 0.0
    private var camHdg = 0f
    private var camInit = false
    private var lastTickNs = 0L          // for framerate-independent smoothing

    // Dead-reckoning: last GPS fix + its velocity, so the camera keeps gliding forward
    // between the 1 Hz fixes instead of easing to a stop (the "laggy" feel at speed).
    private var fixLat = 0.0
    private var fixLng = 0.0
    private var fixBearing = 0f
    private var fixSpeed = 0f             // m/s
    private var fixWallMs = 0L
    private var lastFixTime = 0L
    private var gpsFixIntervalMs = 0.0   // EMA of the gap between fixes — exposes screen-off GPS throttling

    // GPS health surfaced to the dash + phone (computed each tick from accuracy + fix age).
    @Volatile private var gpsStatus = GpsStatus.LOST
    // Held marker position while stationary — deadbands sub-accuracy GPS wander so a parked bike's
    // dot doesn't drift/jitter. Updated to the live fix while moving; reset on teardown.
    private var heldRider: Pair<Double, Double>? = null

    // Smoothed rider position shown on the dash frame (locked to the camera centre so the
    // marker stays put and the map slides under it). null = no GPS.
    @Volatile private var frameRiderLat: Double? = null
    @Volatile private var frameRiderLng: Double? = null

    // Stable ETA (Google-like): the raw estimate jitters with instantaneous speed, so we
    // smooth it and only recompute the absolute arrival clock occasionally — no per-frame churn.
    private var smoothEtaSec = 0.0
    @Volatile private var etaArrivalMs = 0L
    private var lastArrivalCalcMs = 0L
    // The minutes-remaining shown to the rider, refreshed on the same 5 s cadence as the arrival
    // clock so it stops ticking/bouncing every second ("tacky ETA"). null = no active ETA.
    @Volatile private var displayedEtaMin: Int? = null

    // Off-route → reroute, debounced. Now feasible because the app keeps cellular
    // internet while bound to the dash (per-socket binding), so Router can run mid-ride.
    @Volatile private var offRouteSince = 0L
    @Volatile private var lastRerouteAt = 0L
    @Volatile private var rerouting = false
    // Debounce for the "Recalculating" state (ignore 1-frame off-route GPS blips).
    @Volatile private var offRouteStreakStart = 0L
    // Keep "Recalculating" on screen a beat after it clears, so a fast reroute is still seen.
    @Volatile private var recalcHoldUntil = 0L
    // Last lateral distance from the route (m) — logged so flyover/off-route thresholds are tunable.
    @Volatile private var lastSnapDist = -1.0
    // Rolling average of actual moving speed (m/s), held through stops — drives a STEADY ETA
    // instead of one that swings with instantaneous speed at every brake/light.
    private var smoothSpeedMps = 0.0

    // Map-matched rider position: snapped onto the route while on it (kills the GPS
    // lane/road jitter), raw GPS when genuinely off-route. Drives the marker + camera.
    @Volatile private var matchedLat: Double? = null
    @Volatile private var matchedLng: Double? = null

    // Frame cache (avoid the expensive redraw when nothing changed)
    private var frameBitmap: Bitmap? = null
    private var lastSignature = ""
    private var lastRedrawAt = 0L

    // Ride diagnostics: one-shot "first frame" marker + a throttle for the periodic ride line.
    @Volatile private var loggedFirstFrame = false
    private var lastDiagMs = 0L

    companion object {
        private const val MANUAL_IDLE_MS = 8_000L
        private const val FORCE_REDRAW_MS = 2_000L
        // How long to keep trying to (re)connect before giving up and freeing all background
        // resources. Covers the flaky initial handshake AND a mid-ride drop; if the dash is truly
        // gone (parked / powered off / out of range) we stop draining instead of retrying forever.
        private const val RECONNECT_GIVEUP_MS = 45_000L
        private const val SMOOTH_TAU = 0.20      // camera smoothing time constant (s) — tighter so the map trails the bike less
        // GPS bearing is only meaningful while genuinely moving; below this it's noise that
        // would spin the heading-up map (the "turns to the opposite direction when I stop" bug).
        private const val BEARING_MIN_SPEED = 2.0f   // m/s (~7 km/h)
        // Lead the displayed position by the pipeline latency (camera ease + H.264 encode + RTP +
        // dash decode/display ≈ 0.5 s) so the dash shows where the bike IS, not where it was.
        private const val DISPLAY_LEAD_S = 0.4
        // Cap on how far ahead the constant-velocity predictor projects (s of travel). Bounds the
        // over-shoot if a fix is missed across a real stop / a long dropout; below this it tracks
        // the rider's actual motion so the camera never freezes between sparse (screen-off) fixes.
        private const val MAX_PREDICT_S = 3.0
        // Frame rate — encoder loop + projection keep-alive (DashSession.PROJ_HB_MS) MUST match.
        // 4 → 12 (delivered ~10, steady, thermal OK) → now 24 since 12 held. The phone may not
        // deliver a full 24 (per-frame render+encode cost caps it); the ride log's frames= shows the
        // real rate. Drop back toward 12 if the dash blinks/stutters.
        private const val FPS = 24
        private const val MAX_AUTH_ATTEMPTS = 4   // the fw 11.63 handshake often needs a couple of tries
    }

    /** Project a lat/lng forward [distM] metres along [bearingDeg] (great-circle). */
    private fun project(lat: Double, lng: Double, bearingDeg: Double, distM: Double): Pair<Double, Double> {
        val r = 6_371_000.0
        val br = Math.toRadians(bearingDeg)
        val dr = distM / r
        val lat1 = Math.toRadians(lat); val lng1 = Math.toRadians(lng)
        val lat2 = Math.asin(Math.sin(lat1) * Math.cos(dr) + Math.cos(lat1) * Math.sin(dr) * Math.cos(br))
        val lng2 = lng1 + Math.atan2(
            Math.sin(br) * Math.sin(dr) * Math.cos(lat1),
            Math.cos(dr) - Math.sin(lat1) * Math.sin(lat2),
        )
        return Math.toDegrees(lat2) to Math.toDegrees(lng2)
    }

    /**
     * Distance (m) to dead-reckon ahead of the last GPS fix, given the time since it arrived.
     *
     * CONSTANT velocity (distance = speed × time), capped at [MAX_PREDICT_S]. The old version
     * tapered off after ~1 s, so the camera DECELERATED to a near-stop between fixes and then
     * jumped when the next one landed — the "stuck then teleport" feel, especially with the screen
     * off when GPS callbacks slow to multi-second gaps. Projecting at the true speed keeps the
     * camera (and the constant [DISPLAY_LEAD_S] lead) moving smoothly through the gap, so each new
     * fix lands where the camera already is — no jump. The cap bounds over-shoot if the rider
     * stops/turns mid-gap; the next fix (with speed ~0 → predictor off) then eases it back.
     */
    private fun predictedDistance(speedMps: Float, elapsedSec: Double): Double =
        speedMps * elapsedSec.coerceIn(0.0, MAX_PREDICT_S)

    init {
        // Reflect the rider's stored dash WiFi config (SSID may be blank until discovered).
        _ui.update { it.copy(ssid = dashConfig.ssid, wifiPassword = dashConfig.password) }

        // When we connect to a previously-unknown dash by prefix, learn + persist its exact
        // SSID so subsequent connects target it directly (no system picker again).
        wifiManager.onSsidResolved = { learned ->
            dashConfig.ssid = learned
            _ui.update { it.copy(ssid = learned) }
        }

        viewModelScope.launch {
            wifiManager.state.collect { ws ->
                com.example.northstar.data.RideDiagnostics.log(
                    "wifi",
                    ws.status.toString() +
                        (ws.ssid.takeIf { it.isNotBlank() }?.let { " ssid=$it" } ?: "") +
                        (ws.error?.let { " err=$it" } ?: ""),
                )
                when (ws.status) {
                    WifiConnStatus.CONNECTED -> {
                        refreshStage()
                        if (userWantsConnection &&
                            session.state.value in listOf(DashState.IDLE, DashState.ERROR)
                        ) {
                            delay(1_200)
                            // Re-check: the user may have hit Disconnect during the delay.
                            if (userWantsConnection &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED
                            ) session.connect(_ui.value.ssid, wifiManager.network)
                        }
                    }
                    WifiConnStatus.ERROR -> {
                        // Dash is gone for good (bike powered off / out of range) → the ride is
                        // over. Save it even though the user never tapped Disconnect.
                        stopRecording()
                        // …and release everything connect() acquired (wake/wifi locks, GPS,
                        // encoder loop). These used to leak when a connection ENDED on failure
                        // rather than via the Disconnect button, draining the battery in the
                        // background until the app was force-closed.
                        userWantsConnection = false
                        releaseBackgroundResources()
                        session.disconnect()   // wifi status stays ERROR → stage remains ERROR
                        _ui.update { it.copy(errorMessage = ws.error) }; refreshStage()
                        com.example.northstar.data.RideDiagnostics.stop("wifi error / dash gone")
                    }
                    else -> refreshStage()
                }
            }
        }

        viewModelScope.launch {
            session.state.collect { state ->
                com.example.northstar.data.RideDiagnostics.log("session", "→ $state")
                refreshStage()
                // Battery backstop: count down to a full give-up unless/until we're actually
                // streaming. Reaching STREAMING cancels it; any non-streaming state (re)arms it.
                if (state == DashState.STREAMING) cancelReconnectGiveup()
                else if (userWantsConnection) armReconnectGiveup()
                when (state) {
                    DashState.READY -> { authAttempts = 0; _ui.update { it.copy(retryAttempt = 0) }; startStream() }
                    DashState.STREAMING -> {
                        authAttempts = 0; _ui.update { it.copy(retryAttempt = 0, errorMessage = null) }
                        // Usage: the dash actually started showing the map — the core feature working.
                        com.example.northstar.util.Telemetry.logEvent("dash_streaming")
                    }
                    // The handshake to fw 11.63 is flaky — a single failed attempt is normal.
                    // Auto-retry (WiFi is already up) instead of making the rider re-tap Connect.
                    DashState.ERROR -> {
                        if (userWantsConnection &&
                            wifiManager.state.value.status == WifiConnStatus.CONNECTED &&
                            authAttempts < MAX_AUTH_ATTEMPTS
                        ) {
                            authAttempts++
                            _ui.update { it.copy(retryAttempt = authAttempts, errorMessage = null) }
                            delay(1_500)
                            if (userWantsConnection && wifiManager.state.value.status == WifiConnStatus.CONNECTED)
                                session.connect(_ui.value.ssid, wifiManager.network)
                        } else {
                            _ui.update { it.copy(retryAttempt = 0) }
                            // Retries exhausted while the WiFi link is still up → give up fully now
                            // (don't wait out the timer). giveUp() also stops the Wi‑Fi reconnect loop.
                            if (userWantsConnection && authAttempts >= MAX_AUTH_ATTEMPTS &&
                                wifiManager.state.value.status == WifiConnStatus.CONNECTED
                            ) {
                                giveUp("auth exhausted after $authAttempts attempts")
                            }
                        }
                    }
                    else -> {}
                }
            }
        }

        session.onError = { msg ->
            com.example.northstar.data.RideDiagnostics.log("error", msg)
            _ui.update { it.copy(errorMessage = msg) }; refreshStage()
        }
        // Joystick → map zoom only. RIGHT (0x09) = zoom in, LEFT (0x0A) = zoom out.
        // No exit gesture, no other map control (media section is for media).
        session.onButton = { btn ->
            val code = btn.toInt() and 0xFF
            val label = when (code) {
                0x09 -> { zoomIn();  "Zoom in (right)" }
                0x0A -> { zoomOut(); "Zoom out (left)" }
                else -> "code 0x${code.toString(16).uppercase()}"
            }
            _ui.update { it.copy(lastButton = label) }
        }
    }

    private fun refreshStage() {
        val wifi = wifiManager.state.value.status
        val dash = session.state.value
        val stage = when {
            dash == DashState.STREAMING -> ConnStage.STREAMING
            dash == DashState.ERROR || wifi == WifiConnStatus.ERROR -> ConnStage.ERROR
            dash == DashState.AUTHENTICATING || dash == DashState.CONNECTING || dash == DashState.READY -> ConnStage.AUTH
            wifi == WifiConnStatus.REQUESTING || wifi == WifiConnStatus.CONNECTED -> ConnStage.WIFI
            else -> ConnStage.OFFLINE
        }
        _ui.update { it.copy(stage = stage) }
    }

    // ── Connection ─────────────────────────────────────────────────────────

    fun connect() {
        userWantsConnection = true
        authAttempts = 0
        _ui.update { it.copy(errorMessage = null, retryAttempt = 0, needsWifiOn = false) }

        // The dash link needs the Wi‑Fi radio on (WifiNetworkSpecifier joins it as a separate
        // per-app network — the phone's current Wi‑Fi/cellular is untouched). Android 10+ won't
        // let us enable Wi‑Fi for the user, so if it's off, surface it immediately and let the UI
        // open the Wi‑Fi panel — far better than a silent 30s timeout.
        if (!wifiManager.isWifiEnabled()) {
            userWantsConnection = false
            _ui.update { it.copy(stage = ConnStage.ERROR, needsWifiOn = true,
                errorMessage = "Turn on Wi‑Fi to connect to the dash") }
            return
        }
        com.example.northstar.data.RideDiagnostics.init(getApplication())
        com.example.northstar.data.RideDiagnostics.start("connect")
        // Stamp the running build's identity into the log so a pulled ride is matched 1:1 to the
        // pushed APK — never again analyse a ride and not know which build produced it.
        com.example.northstar.data.RideDiagnostics.log(
            "build", "apk=${com.example.northstar.util.BuildId.sha12(getApplication())}" +
                " vc=${com.example.northstar.BuildConfig.VERSION_CODE} name=${com.example.northstar.BuildConfig.VERSION_NAME}",
        )
        com.example.northstar.data.RideDiagnostics.log(
            "connect", "ssid='${dashConfig.ssid}' needsDiscovery=${dashConfig.needsDiscovery} dest=${_ui.value.destinationName}",
        )
        loggedFirstFrame = false
        DashKeepAliveService.start(getApplication())
        location.start()
        startRecording()

        // We must know the EXACT dash SSID before connecting — the dash validates it inside
        // the encrypted auth handshake (DashAuth), and Android redacts the SSID of a network
        // we've already joined. So if we don't have it stored, find it from a WiFi scan.
        if (dashConfig.needsDiscovery) {
            wifiManager.findDashSsid(dashConfig.ssidPrefix)?.let { found ->
                dashConfig.ssid = found
                _ui.update { it.copy(ssid = found) }
            }
        }

        when {
            wifiManager.state.value.status == WifiConnStatus.CONNECTED ->
                session.connect(_ui.value.ssid, wifiManager.network)
            // Known SSID (stored or just found by scan) → exact connect + correct auth.
            dashConfig.ssid.isNotBlank() ->
                wifiManager.connect(dashConfig.ssid, dashConfig.password)
            // Couldn't find it in scan results — fall back to prefix discovery so we at
            // least associate (auth may still need the SSID; a rescan usually fixes it).
            else ->
                wifiManager.connect(dashConfig.ssidPrefix, dashConfig.password, prefixMatch = true)
        }
    }

    fun disconnect() {
        userWantsConnection = false
        cancelReconnectGiveup()
        com.example.northstar.data.RideDiagnostics.log("connect", "user disconnect")
        stopRecording()        // a connect→disconnect session = one saved ride
        session.disconnect()
        wifiManager.disconnect()
        releaseBackgroundResources()
        refreshStage()
        com.example.northstar.data.RideDiagnostics.stop("disconnect")
        // Ride just ended and internet is back (dash WiFi released) — flush the log to Firestore
        // now so it's retrievable even if the app isn't reopened. Test-channel + Firebase-gated.
        com.example.northstar.data.DiagnosticsUploader.uploadPending(getApplication())
    }

    /**
     * Release everything that keeps the CPU/GPS/WiFi awake: the foreground service
     * (PARTIAL_WAKE_LOCK + WifiLock), GPS updates, and the encoder/stream loop. Called
     * whenever the connection has ended so the app stops draining the battery in the
     * background — on an explicit disconnect, on a terminal connection failure (dash
     * unreachable / handshake exhausted), and when the ViewModel is cleared.
     */
    private fun releaseBackgroundResources() {
        teardown()                                   // stream loop + encoder + frame bitmap
        location.stop()                              // stop GPS updates
        DashKeepAliveService.stop(getApplication())  // release wake + wifi locks
    }

    /**
     * Complete stop of all background work — including the Wi‑Fi manager's reconnect loop, which
     * [releaseBackgroundResources] alone does NOT stop (that loop re-joins the dash AP every few
     * seconds and was the main source of drain after the dash went away). The session stays in
     * ERROR so the Dash screen still shows "Couldn't connect" + Try again.
     */
    private fun giveUp(reason: String) {
        cancelReconnectGiveup()
        userWantsConnection = false
        session.disconnect()
        wifiManager.disconnect()       // stops the indefinite AP reconnect loop
        releaseBackgroundResources()
        refreshStage()
        com.example.northstar.data.RideDiagnostics.log("error", "gave up: $reason — background released")
        com.example.northstar.data.RideDiagnostics.stop(reason)
    }

    /** Start the give-up countdown if it isn't already running (idempotent). */
    private fun armReconnectGiveup() {
        if (giveupJob?.isActive == true) return
        giveupJob = viewModelScope.launch {
            delay(RECONNECT_GIVEUP_MS)
            if (session.state.value != DashState.STREAMING) giveUp("reconnect window elapsed")
        }
    }

    private fun cancelReconnectGiveup() { giveupJob?.cancel(); giveupJob = null }

    // ── Ride recording (the connected session) ───────────────────────────────
    private fun startRecording() {
        if (recorder.isRecording) return
        recorder.start()
        recordJob = viewModelScope.launch {
            location.location.collect { loc ->
                if (loc != null) recorder.add(loc.latitude, loc.longitude, loc.speed, loc.accuracy, loc.time)
            }
        }
    }

    private fun stopRecording() {
        recordJob?.cancel(); recordJob = null
        if (!recorder.isRecording) return
        val ride = recorder.stop() ?: return   // null = trivial session, don't save
        repo.addRide(ride)   // self-scoped on the repo (survives ViewModel teardown)
        android.util.Log.i("DashViewModel", "Ride saved: ${"%.1f".format(ride.distanceKm)} km, ${ride.durationSec}s")
    }

    // ── Dash WiFi config (Settings) ──────────────────────────────────────────
    fun setSsid(s: String) { dashConfig.ssid = s.trim(); _ui.update { it.copy(ssid = s.trim()) } }
    fun setWifiPassword(p: String) { dashConfig.password = p; _ui.update { it.copy(wifiPassword = p) } }
    /** Forget the paired dash so the next connect rediscovers any RE_* dash by prefix. */
    fun forgetDash() { dashConfig.forgetDash(); _ui.update { it.copy(ssid = "") } }

    // ── Destination + routing ───────────────────────────────────────────────

    fun prefetchTiles(lat: Double, lng: Double) {
        val loc = location.location.value
        tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
    }

    fun setDestination(name: String, lat: Double?, lng: Double?) {
        _ui.update { it.copy(
            destinationName = name, hasRoute = false,
            destLatLng = if (lat != null && lng != null) lat to lng else null,
            routePoints = emptyList(),
        ) }
        destLat = lat
        destLng = lng
        route = null
        progressM = 0.0
        smoothEtaSec = 0.0; etaArrivalMs = 0L; displayedEtaMin = null   // fresh ETA for the new route
        voice.resetTrip()   // fresh announcements for the new route
        session.updateRouteCard(name)
        if (lat != null && lng != null) {
            val loc = location.lastKnown()
            tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
            fetchRoute(lat, lng)
        }
    }

    /** Drop the destination/route → free roam. The map keeps streaming and follows the rider. */
    fun exitNavigation() {
        destLat = null
        destLng = null
        route = null
        progressM = 0.0
        offRouteSince = 0L
        panX = 0f; panY = 0f; followMode = true
        smoothEtaSec = 0.0; etaArrivalMs = 0L; displayedEtaMin = null
        voice.resetTrip()
        lastSignature = ""   // force a redraw with no route line
        _ui.update { it.copy(
            destinationName = null,
            hasRoute = false,
            destLatLng = null,
            routePoints = emptyList(),
            remainingKm = null,
            etaMinutes = null,
            maneuver = null,
            offRoute = false,
            followMode = true,
        ) }
        session.updateRouteCard("Northstar")   // dash card → name + 0.0 km, nav off
    }

    /** Compute the road route now (while internet is reachable) and cache it. */
    private fun fetchRoute(destLatV: Double, destLngV: Double) {
        val loc = location.lastKnown()
        if (loc == null) {
            android.util.Log.w("DashViewModel", "fetchRoute: no origin location yet")
            return
        }
        viewModelScope.launch {
            val r = Router.route(GeoPoint(loc.latitude, loc.longitude), GeoPoint(destLatV, destLngV))
            if (r != null) {
                route = r
                tiles.prefetchRoute(r.geometry)
                _ui.update { it.copy(hasRoute = true, routePoints = r.geometry) }
                android.util.Log.i("DashViewModel", "Route ready: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m")
            } else {
                android.util.Log.w("DashViewModel", "Router returned null")
            }
        }
    }

    // ── Map controls ────────────────────────────────────────────────────────

    fun zoomIn()  = setZoom(zoom + 1)
    fun zoomOut() = setZoom(zoom - 1)
    private fun setZoom(z: Int) {
        val clamped = z.coerceIn(11, 20)
        if (clamped == zoom) return
        zoom = clamped
        _ui.update { it.copy(mapZoom = zoom) }
        // Make zoom feel instant: force a redraw on the very next tick (the renderer bridges the
        // gap with scaled neighbouring-zoom tiles), and warm the real tiles for the new level so
        // the scaled view sharpens in quickly.
        lastSignature = ""
        if (camInit) tiles.prefetchZoom(camLat, camLng, zoom)
    }
    fun panBy(dx: Float, dy: Float) = manualPan(dx, dy)
    fun recenter() {
        panX = 0f; panY = 0f; followMode = true
        _ui.update { it.copy(followMode = true) }
    }
    fun toggleHeadingUp() {
        headingUp = !headingUp
        _ui.update { it.copy(headingUp = headingUp) }
    }

    private fun manualPan(dx: Float, dy: Float) {
        panX += dx; panY += dy
        followMode = false
        lastManualPanAt = System.currentTimeMillis()
        _ui.update { it.copy(followMode = false) }
    }

    // ── Video + nav loop ────────────────────────────────────────────────────

    private fun startStream() {
        com.example.northstar.data.RideDiagnostics.log("stream", "startStream — encoder up, RTP→dash beginning")
        videoPtsMs = 0L
        val packetizer = RtpPacketizer { rtpPkt -> session.sendRtp(rtpPkt) }
        val nalProc    = NalProcessor { nal, _ ->
            // Even, monotonic presentation timestamp (set per frame by the send loop) instead of
            // wall clock — so the dash sees a clean, evenly-spaced timeline regardless of tiny
            // send-time wobble. All NALs of one frame share the same value.
            packetizer.packetize(nal, endOfAU = true, wallClockMs = videoPtsMs)
        }
        val onEncoded: (ByteArray, Boolean) -> Unit = { annexB, isKey ->
            nalProc.process(annexB)
            // First encoded frame leaving the phone — its delay after READY is the prime suspect
            // for the dash's "Timeout!" (the dash waits for video and gives up if it's late).
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                com.example.northstar.data.RideDiagnostics.log("stream", "first video frame sent (key=$isKey, ${annexB.size}B)")
            }
            // Atomic update: this runs on the encoder's callback thread, concurrent with the
            // frame loop's _ui writes — a plain copy() read-modify-write would drop updates.
            _ui.update { it.copy(frameCount = it.frameCount + 1) }
        }
        encoder?.release()
        encoder = DashEncoder(onEncoded).also { it.prepare() }

        frameBitmap = Bitmap.createBitmap(DashEncoder.WIDTH, DashEncoder.HEIGHT, Bitmap.Config.ARGB_8888)
        lastSignature = ""
        // Fresh camera so it snaps to the first fix instead of gliding from a stale spot.
        camInit = false; lastTickNs = 0L; lastFixTime = 0L; heldRider = null

        session.startStreaming()
        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }

        streamJob = viewModelScope.launch(Dispatchers.Default) {
            var lastPrefetch = 0L
            var failures = 0
            // Absolute-grid pacing: aim each frame at start + n·interval and sleep only the
            // REMAINDER, instead of a fixed delay tacked onto variable render work. That work used
            // to ride on top of the delay, so the cadence wobbled (and the net rate fell to ~15 fps)
            // — uneven arrival is exactly what makes the dash decoder jitter/teleport. Now the
            // interval is constant as long as work fits inside it; an occasional overrun re-syncs to
            // the grid without bursting catch-up frames.
            val frameMs = 1000L / FPS
            var nextFrameMs = System.currentTimeMillis()
            var lastSendMs = System.currentTimeMillis()
            // The loop must NEVER die silently: the session's heartbeats keep the dash
            // connected, so a dead frame loop = frozen map with the connection "up".
            while (isActive && session.state.value == DashState.STREAMING) {
                try {
                    tick()
                    val bmp = frameBitmap
                    val enc = encoder
                    if (bmp != null && enc != null) {
                        enc.renderFrame { canvas -> canvas.drawBitmap(bmp, 0f, 0f, null) }
                        // Advance the even presentation clock by one frame BEFORE draining, so the
                        // frame(s) pulled this iteration carry an evenly-spaced RTP timestamp.
                        videoPtsMs += frameMs
                        enc.drain()
                    }
                    failures = 0
                    // Track the real send cadence (EMA + worst) so the ride log proves it's even.
                    val sendNow = System.currentTimeMillis()
                    val gap = sendNow - lastSendMs
                    lastSendMs = sendNow
                    sendPaceMs = if (sendPaceMs <= 0.0) gap.toDouble() else sendPaceMs + (gap - sendPaceMs) * 0.1
                    if (gap > sendPaceMaxMs) sendPaceMaxMs = gap
                    // Warm the tile cache ahead of the rider every ~20 s.
                    if (sendNow - lastPrefetch > 20_000) {
                        lastPrefetch = sendNow
                        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    android.util.Log.e("DashViewModel", "Frame loop error #$failures", e)
                    if (failures >= 3) {
                        // MediaCodec is in an error state — rebuild the encoder so the
                        // stream recovers. The fresh encoder re-emits SPS/PPS, which the
                        // NAL processor bundles into the next IDR for the dash decoder.
                        runCatching { encoder?.release() }
                        encoder = runCatching { DashEncoder(onEncoded).also { it.prepare() } }
                            .onFailure { android.util.Log.e("DashViewModel", "Encoder rebuild failed", it) }
                            .getOrNull()
                        lastSignature = "" // force a full redraw on the next tick
                        failures = 0
                    }
                }
                // Sleep only the remaining time to the next grid point.
                nextFrameMs += frameMs
                val sleepMs = nextFrameMs - System.currentTimeMillis()
                if (sleepMs > 1) {
                    delay(sleepMs)
                } else if (sleepMs < -frameMs) {
                    // Fell more than a frame behind (render spike) — re-anchor so we don't fire a
                    // burst of catch-up frames (which would itself look like a jump).
                    nextFrameMs = System.currentTimeMillis()
                }
            }
        }
    }

    /** Compute nav state, push nav-info to the dash, and redraw the frame only if it changed. */
    private fun tick() {
        // Revert to follow mode after the rider stops nudging the joystick.
        if (!followMode && System.currentTimeMillis() - lastManualPanAt > MANUAL_IDLE_MS) {
            panX = 0f; panY = 0f; followMode = true
            _ui.update { it.copy(followMode = true) }
        }

        val loc = location.location.value
        val r = route
        val dLat = destLat; val dLng = destLng

        // Default to raw GPS; map-matching below snaps it onto the route when on it.
        matchedLat = loc?.latitude
        matchedLng = loc?.longitude

        var remainingM: Double? = null
        var etaSec: Double? = null
        // Heading-up direction. GPS bearing is trustworthy ONLY while genuinely moving — at a
        // standstill (or a near-zero crawl) it's noise and would rotate the map to a random or
        // opposite direction the instant you stop. So adopt it only above BEARING_MIN_SPEED (and
        // when the fix actually carries a bearing); otherwise HOLD the last heading shown while
        // moving. Also keeps the last heading through a GPS dropout (tunnels).
        var heading = when {
            loc != null && loc.hasBearing() && loc.speed >= BEARING_MIN_SPEED -> loc.bearing
            camInit -> camHdg
            else -> 0f
        }
        var offRoute = false
        var recalculating = false

        if (r != null && loc != null) {
            val ns = trackProgress(r, GeoPoint(loc.latitude, loc.longitude))
            remainingM = ns.remainingM
            // Heading-aware off-route. A flyover (or its service road) runs PARALLEL to and almost
            // on top of the route, so flat GPS can sit well past the lateral threshold while the
            // rider is still effectively on the line. Distance alone then cries "off-route" and
            // triggers a sudden reroute. So in the ambiguous band we require the heading to ALSO
            // disagree (a genuine divergence / wrong turn); a clearly different road (very far) is
            // off-route regardless. This is the main flyover-confusion mitigation.
            lastSnapDist = ns.snapDist
            val headingKnown = loc.hasBearing() && loc.speed >= BEARING_MIN_SPEED
            val headingOff = headingKnown && angleDelta(loc.bearing, ns.heading) > 50f
            offRoute = when {
                ns.snapDist > 150.0 -> true       // unmistakably a different road → off regardless
                ns.snapDist > 70.0  -> headingOff // parallel/flyover band: off ONLY if heading diverges
                else -> false                     // (stopped here → not off-route; decide once moving)
            }
            // NOTE: no marker snapping. Raw GPS is accurate; snapping to the route
            // polyline pinned the rider onto a parallel road in dense areas (showed
            // "Indira Enclave" when actually at "Isha"). trackProgress is still used
            // for nav distances + off-route/reroute detection, just not to move the marker.
            // (Heading is handled above — held steady at low speed, not snapped to the route
            // segment, so a stop doesn't rotate the map.)
            val nowMs = System.currentTimeMillis()

            // "Recalculating" = off the line for >0.8 s (ignores a 1-frame GPS blip), OR a reroute
            // request is in flight. While true the distance is measured against a route we've left,
            // so the ETA is meaningless — we FREEZE it (and the pill says "Recalculating") rather
            // than let the number lurch until the fresh route lands. recalcHoldUntil keeps the pill
            // up a beat after the new route lands so a fast reroute is still visible to the rider.
            if (offRoute) { if (offRouteStreakStart == 0L) offRouteStreakStart = nowMs }
            else offRouteStreakStart = 0L
            val rawRecalc = rerouting || (offRouteStreakStart != 0L && nowMs - offRouteStreakStart > 800)
            if (rawRecalc) recalcHoldUntil = nowMs + 1_500
            recalculating = rawRecalc || nowMs < recalcHoldUntil

            if (!recalculating) {
                // Steady ETA: divide remaining distance by a ROLLING AVERAGE speed (held through
                // stops), not the instantaneous value — so it tracks a typical pace instead of
                // swinging every time you brake, accelerate or sit at a light.
                if (loc.speed > 0.5f) {
                    smoothSpeedMps = if (smoothSpeedMps <= 0.0) loc.speed.toDouble()
                                     else smoothSpeedMps + (loc.speed - smoothSpeedMps) * 0.05
                }
                val speed = smoothSpeedMps.coerceAtLeast(3.0)  // floor: no absurd ETA at a crawl
                val rawEta = ns.remainingM / speed
                smoothEtaSec = if (smoothEtaSec <= 0.0) rawEta else smoothEtaSec + (rawEta - smoothEtaSec) * 0.08
                // Refresh the displayed minutes + arrival clock only every 5 s so they read steady
                // (like Google Maps) instead of flickering every second.
                if (etaArrivalMs == 0L || nowMs - lastArrivalCalcMs > 5_000) {
                    etaArrivalMs = nowMs + (smoothEtaSec * 1000).toLong()
                    displayedEtaMin = Math.round(smoothEtaSec / 60.0).toInt()
                    lastArrivalCalcMs = nowMs
                }
            }
            etaSec = if (smoothEtaSec > 0.0) smoothEtaSec else null
            // Feed the dash's own turn-by-turn widget with CORRECT distances (next-turn
            // + total remaining) and the (held) arrival time. Glyph stays CONTINUE until
            // other codes are verified.
            val (pv, pu) = toDashDistance(ns.nextTurnM)
            val (tv, tu) = toDashDistance(ns.remainingM)
            val etaHHMM = etaSec?.let {
                val arrival = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, it.toInt()) }
                "%02d%02d".format(arrival.get(java.util.Calendar.HOUR_OF_DAY), arrival.get(java.util.Calendar.MINUTE))
            }
            session.updateNavInfo(DashCommands.NAV_MANEUVER_CONTINUE, pv, pu, tv, tu, etaHHMM)
            // Spoken/chime turn guidance (no-op when voice mode is OFF).
            voice.maybeAnnounce(ns.nextManeuver, ns.nextTurnM, ns.remainingM)
        } else if (loc != null && dLat != null && dLng != null) {
            remainingM = GeoPoint.distMeters(
                GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng)
            )
        }

        // Recompute the route if the rider has clearly left it for a few seconds.
        maybeReroute(offRoute, loc)

        // GPS health: LOST if no fresh fix for a few seconds (or none at all), WEAK if the fix is
        // imprecise or coming in slowly, else GOOD. Drives the on-dash indicator and marker colour.
        val fixAgeMs = if (fixWallMs > 0L) System.currentTimeMillis() - fixWallMs else Long.MAX_VALUE
        gpsStatus = when {
            loc == null || fixAgeMs > 4_000 -> GpsStatus.LOST
            loc.accuracy > 25f || gpsFixIntervalMs > 2_500 -> GpsStatus.WEAK
            else -> GpsStatus.GOOD
        }

        // Publish nav figures to the phone UI.
        _ui.update { it.copy(
            hasGps = loc != null,
            gpsStatus = gpsStatus,
            riderLat = matchedLat,
            riderLng = matchedLng,
            riderBearing = heading,
            remainingKm = remainingM?.let { d -> d / 1000.0 },
            etaMinutes = if (etaSec != null) displayedEtaMin else null,
            maneuver = null,
            offRoute = offRoute,
            recalculating = recalculating,
        ) }

        updateThermal()

        // Periodic ride snapshot (~15 s) — GPS quality, motion, frame throughput and thermal,
        // so a post-ride read shows where the map stuttered (weak GPS) or the dash struggled.
        val nowD = System.currentTimeMillis()
        if (nowD - lastDiagMs > 15_000) {
            lastDiagMs = nowD
            com.example.northstar.data.RideDiagnostics.log(
                "ride",
                "gps=" + (loc?.let { "acc=%.0fm spd=%.1fm/s".format(it.accuracy, it.speed) } ?: "none") +
                    " fixGap=%.0fms".format(gpsFixIntervalMs) +
                    " frames=${_ui.value.frameCount} thermal=${_ui.value.thermal}" +
                    " pace=%.0fms/max=%dms".format(sendPaceMs, sendPaceMaxMs) +  // frame-send cadence (even ≈ 1000/FPS)
                    " remaining=" + (remainingM?.let { "%.1fkm".format(it / 1000.0) } ?: "-") +
                    " offRoute=$offRoute" +
                    (if (lastSnapDist >= 0) " snap=%.0fm".format(lastSnapDist) else ""),
            )
            sendPaceMaxMs = 0L  // reset the worst-case window each diag line
        }

        // ── Predictive, framerate-independent camera (CarPlay-smooth) ──
        // Capture each fresh GPS fix + its velocity for dead-reckoning.
        if (loc != null && loc.time != lastFixTime) {
            lastFixTime = loc.time
            // Track the GAP between fixes (EMA): if this balloons past ~1 s with the screen off,
            // GPS callbacks are being throttled — that's what the predictor has to bridge and the
            // root of any residual "stuck then teleport". Logged in the periodic ride line.
            val nowWall = System.currentTimeMillis()
            if (fixWallMs > 0L) {
                val gap = (nowWall - fixWallMs).toDouble()
                gpsFixIntervalMs = if (gpsFixIntervalMs <= 0.0) gap else gpsFixIntervalMs + (gap - gpsFixIntervalMs) * 0.3
            }
            fixLat = loc.latitude; fixLng = loc.longitude
            fixBearing = loc.bearing; fixSpeed = loc.speed
            fixWallMs = nowWall
        }
        // Extrapolate where the rider IS NOW (last fix + velocity·elapsed), so the camera
        // doesn't trail the bike between 1 Hz fixes. Only predict while genuinely moving.
        val rider: Pair<Double, Double>? = when {
            loc == null -> null
            fixSpeed > 0.5f -> {
                // Dead-reckon forward from the last fix so the camera keeps GLIDING through GPS
                // gaps instead of freezing then snapping ("stuck then jump"). predictedDistance
                // runs at full speed for the first ~1 s (the normal 1 Hz fix gap) then tapers, so
                // a long dropout — or a brake/stop the predictor hasn't heard about yet — can't
                // fling the camera far ahead; the next fix then needs only a tiny eased correction.
                // elapsed = time since the fix arrived; + DISPLAY_LEAD_S leads the marker past
                // "now" to cancel the camera-ease + encode + RTP + dash-decode/display latency, so
                // the dash tracks the bike instead of trailing ~0.5 s behind. The predictor's taper
                // still caps how far a long dropout / unannounced brake can fling it.
                val elapsed = (System.currentTimeMillis() - fixWallMs) / 1000.0
                // Weak-GPS guard: a noisy fix has an unreliable position AND bearing/speed, so
                // extrapolating from it flings the view ("teleport"). Scale the prediction down as
                // accuracy worsens — full below 12 m, none above 40 m — so a poor signal just eases
                // (heavily smoothed) toward the raw fix instead of dead-reckoning off a bad vector.
                val conf = ((40f - (loc.accuracy)) / 28f).toDouble().coerceIn(0.0, 1.0)
                val dist = predictedDistance(fixSpeed, elapsed + DISPLAY_LEAD_S) * conf
                // Remember where the bike actually is, so that when it stops the marker holds the
                // real fix (not the lead-projected point ahead of it).
                heldRider = fixLat to fixLng
                project(fixLat, fixLng, fixBearing.toDouble(), dist)
            }
            else -> {
                // Stopped: hold position and IGNORE sub-accuracy GPS wander, so a parked bike's dot
                // doesn't drift or jitter. Only move the held point if the fix steps clearly outside
                // its own error radius (i.e. the bike really moved a little / GPS re-converged).
                val cand = matchedLat!! to matchedLng!!
                val h = heldRider
                val band = loc.accuracy.coerceIn(8f, 30f).toDouble()
                if (h == null || GeoPoint.distMeters(
                        GeoPoint(h.first, h.second), GeoPoint(cand.first, cand.second)
                    ) > band
                ) { heldRider = cand; cand } else h
            }
        }

        val haveTarget = rider != null || (dLat != null && dLng != null)
        val targetLat = rider?.first ?: dLat ?: camLat
        val targetLng = rider?.second ?: dLng ?: camLng

        // Time-based smoothing: alpha derived from the real frame interval + a time
        // constant, so motion is equally smooth at any (dynamic) frame rate.
        val nowNs = System.nanoTime()
        val dt = if (lastTickNs == 0L) 0.042 else ((nowNs - lastTickNs) / 1e9).coerceIn(0.0, 0.5)
        lastTickNs = nowNs
        // Adaptive smoothing: ease HARDER (bigger time constant) when the fix is noisy so weak-GPS
        // jitter doesn't reach the camera, and stay snappy when the signal is clean. This is the
        // other half of the "stuck then jump" fix — the predictor bridges gaps, this absorbs the
        // lateral noise that makes a poor signal jitter.
        val acc = loc?.accuracy ?: 12f
        val tau = when {
            acc > 25f -> SMOOTH_TAU * 2.0   // poor signal → heavy smoothing
            acc > 12f -> SMOOTH_TAU * 1.5   // fair
            else      -> SMOOTH_TAU         // good → responsive
        }
        val a = if (camInit) (1.0 - Math.exp(-dt / tau)) else 1.0

        if (haveTarget) {
            if (!camInit) { camLat = targetLat; camLng = targetLng; camHdg = heading; camInit = true }
            else {
                camLat += (targetLat - camLat) * a
                camLng += (targetLng - camLng) * a
                val dh = (((heading - camHdg) % 360f) + 540f) % 360f - 180f  // shortest arc
                camHdg += dh * a.toFloat()
            }
        }
        // Lock the dash marker to the smoothed centre (map slides under it).
        frameRiderLat = if (rider != null) camLat else null
        frameRiderLng = if (rider != null) camLng else null

        val centerLat = if (haveTarget) camLat else 0.0
        val centerLng = if (haveTarget) camLng else 0.0
        val camHeading = if (haveTarget) camHdg else heading

        val sig = buildString {
            // High resolution (6 dp ≈ 0.1 m, 0.1° heading) so every smoothed step redraws
            // for buttery motion. Safe from standstill jitter because the camera is fed the
            // SMOOTHED position (which settles and stops), not raw GPS.
            append("%.6f".format(centerLat)); append("%.6f".format(centerLng))
            append(zoom); append(panX.toInt()); append(panY.toInt())
            append(if (headingUp) (camHeading * 10).toInt() else 0)
            append(remainingM?.let { (it / 100).toInt() } ?: -1) // 100 m resolution to avoid jitter
            append(if (r != null) r.geometry.size else 0)
        }
        val now = System.currentTimeMillis()
        if (sig != lastSignature || now - lastRedrawAt > FORCE_REDRAW_MS) {
            lastSignature = sig
            lastRedrawAt = now
            redrawFrame(centerLat, centerLng, camHeading, remainingM)
        }
    }

    /**
     * Reroute when off the line for >5 s (12 s cooldown between attempts). Routes from
     * the live GPS position to the saved destination and swaps the polyline in. Needs
     * internet — available now because only the dash sockets are bound to the dash WiFi.
     */
    private fun maybeReroute(offRoute: Boolean, loc: android.location.Location?) {
        val dLat = destLat; val dLng = destLng
        if (!offRoute || loc == null || dLat == null || dLng == null) { offRouteSince = 0L; return }
        val now = System.currentTimeMillis()
        if (offRouteSince == 0L) offRouteSince = now
        // Reroute faster: 2 s off-line confirms a real miss (not a GPS wobble), 6 s cooldown
        // between attempts. Was 4 s / 12 s, which felt sluggish after a missed turn.
        if (now - offRouteSince < 2_000 || now - lastRerouteAt < 6_000 || rerouting) return
        lastRerouteAt = now
        rerouting = true
        val offSec = (now - offRouteSince) / 1000
        android.util.Log.i("DashViewModel", "Off-route ${offSec}s → rerouting")
        com.example.northstar.data.RideDiagnostics.log("reroute", "off-route ${offSec}s → requesting new route")
        val startMs = System.currentTimeMillis()
        viewModelScope.launch {
            val r = Router.route(GeoPoint(loc.latitude, loc.longitude), GeoPoint(dLat, dLng))
            val took = System.currentTimeMillis() - startMs
            if (r != null) {
                route = r
                progressM = 0.0
                offRouteSince = 0L
                tiles.prefetchRoute(r.geometry)
                _ui.update { it.copy(hasRoute = true, routePoints = r.geometry) }
                android.util.Log.i("DashViewModel", "Reroute ok: ${r.geometry.size} pts, ${r.totalMeters.toInt()} m")
                com.example.northstar.data.RideDiagnostics.log("reroute", "new route in ${took}ms (${r.geometry.size} pts, ${r.totalMeters.toInt()} m)")
            } else {
                android.util.Log.w("DashViewModel", "Reroute failed (no internet?)")
                com.example.northstar.data.RideDiagnostics.log("reroute", "FAILED after ${took}ms (no internet?)")
            }
            rerouting = false
        }
    }

    private fun redrawFrame(
        centerLat: Double, centerLng: Double, heading: Float, remainingM: Double?,
    ) {
        val bmp = frameBitmap ?: return
        val loc = location.location.value
        // Glanceable ETA — minutes remaining + a stable 12-hour arrival clock. Both come
        // from the smoothed estimate so they don't flicker every second.
        val mins = _ui.value.etaMinutes
        val arriving = mins != null && mins <= 0
        val recalculating = _ui.value.recalculating
        // Off-route / rerouting: tell the rider the ETA is being recomputed instead of showing a
        // frozen or wildly-swinging number.
        val etaPrimary = when {
            recalculating && route != null -> "Recalculating"
            mins != null && etaArrivalMs > 0L -> when {
                arriving   -> "Arriving"
                mins >= 60 -> "${mins / 60}h ${mins % 60}m"
                else       -> "$mins min"
            }
            else -> null
        }
        // 12-hour arrival clock; hidden once arriving or while recalculating.
        val etaSecondary = if (etaPrimary != null && !arriving && !recalculating)
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(etaArrivalMs))
        else null
        val frame = MapRenderer.Frame(
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = zoom,
            panX = panX,
            panY = panY,
            headingUp = headingUp && (loc != null),
            heading = heading,
            riderLat = frameRiderLat,
            riderLng = frameRiderLng,
            destLat = destLat,
            destLng = destLng,
            destName = _ui.value.destinationName,
            route = route?.geometry ?: emptyList(),
            maneuverText = null, // turn-by-turn maneuver banner removed
            remainingText = remainingM?.let { fmtDist(it) },
            // Top-down (heading-up) nav view. The 3D perspective tilt is DISABLED: warping
            // flat raster tiles via setPolyToPoly stretches the baked-in map labels and
            // skews the angle (you can't get true Google-Maps 3D without vector tiles).
            tilt3d = false,
            etaPrimary = etaPrimary,
            etaSecondary = etaSecondary,
            gpsWeak = gpsStatus == GpsStatus.WEAK,
            gpsLost = gpsStatus == GpsStatus.LOST,
        )
        mapRenderer.draw(Canvas(bmp), frame)
        // Publish a snapshot for the in-app preview (immutable copy — see _previewFrame).
        _previewFrame.value = bmp.copy(Bitmap.Config.ARGB_8888, false)
    }

    // ── Monotonic route-progress tracker ────────────────────────────────────
    // Snapping to the GLOBALLY nearest segment makes the remaining distance flicker
    // (a winding route can pass near the start again, so it jumps to ~straight-line).
    // Instead we advance progress along the route, searching a window AHEAD of where
    // we already are, only re-acquiring globally if we're clearly off-route.
    @Volatile private var progressM = 0.0

    private data class NavState(
        val remainingM: Double, val nextTurnM: Double, val heading: Float, val offRoute: Boolean,
        val snapped: GeoPoint, val snapDist: Double,
        val nextManeuver: com.example.northstar.dash.nav.Maneuver?,
    )

    private data class Match(val cum: Double, val dist: Double, val bearing: Float, val proj: GeoPoint)

    private fun trackProgress(r: Route, pos: GeoPoint): NavState {
        val geom = r.geometry
        val cum = r.cumulative

        fun search(lo: Double, hi: Double): Match {
            var bestDist = Double.MAX_VALUE; var bestCum = progressM; var bestBearing = 0f; var bestProj = pos
            for (i in 0 until geom.size - 1) {
                if (cum[i + 1] < lo || cum[i] > hi) continue
                val (proj, t) = GeoPoint.projectOnSegment(pos, geom[i], geom[i + 1])
                val d = GeoPoint.distMeters(pos, proj)
                if (d < bestDist) {
                    bestDist = d
                    bestCum = cum[i] + GeoPoint.distMeters(geom[i], geom[i + 1]) * t
                    bestBearing = GeoPoint.bearing(geom[i], geom[i + 1]).toFloat()
                    bestProj = proj
                }
            }
            return Match(bestCum, bestDist, bestBearing, bestProj)
        }

        var m = search(progressM - 60.0, progressM + 1000.0)
        if (m.dist > 80.0) {
            val g = search(0.0, r.totalMeters) // off-window → re-acquire globally
            if (g.dist < m.dist) m = g
        }
        progressM = maxOf(progressM - 25.0, m.cum) // mostly forward, tolerate small GPS slide

        val remaining = (r.totalMeters - progressM).coerceAtLeast(0.0)
        val nextMan = r.maneuvers.firstOrNull {
            it.cumulativeMeters > progressM + 1.0 && it.type != com.example.northstar.dash.nav.ManeuverType.DEPART
        }
        val nextTurn = nextMan?.let { (it.cumulativeMeters - progressM).coerceAtLeast(0.0) } ?: remaining
        return NavState(remaining, nextTurn, m.bearing, m.dist > 70.0, m.proj, m.dist, nextMan)
    }

    private fun updateThermal() {
        val status = runCatching { powerManager.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        val label = when (status) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> "OK"
            PowerManager.THERMAL_STATUS_MODERATE -> "Warm"
            PowerManager.THERMAL_STATUS_SEVERE, PowerManager.THERMAL_STATUS_CRITICAL -> "Hot"
            else -> "Throttling"
        }
        if (label != _ui.value.thermal) _ui.update { it.copy(thermal = label) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** metres → (value, dash unit). <1 km → metres (0x30); else km×10 (0x10). */
    private fun toDashDistance(meters: Double): Pair<Int, Int> =
        if (meters < 1000.0) meters.toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_METERS
        else (meters / 100.0).toInt().coerceIn(0, 0xFFFF) to DashCommands.NAV_UNIT_KM_TENTHS

    private fun fmtDist(m: Double): String =
        if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000.0)

    /** Smallest absolute angle (0..180°) between two compass bearings. */
    private fun angleDelta(a: Float, b: Float): Float {
        val d = (((a - b) % 360f) + 540f) % 360f - 180f
        return kotlin.math.abs(d)
    }

    private fun teardown() {
        streamJob?.cancel(); streamJob = null
        encoder?.release(); encoder = null
        frameBitmap?.recycle(); frameBitmap = null
        _previewFrame.value = null   // drop the stale dash frame so the preview falls back to the live map
    }

    override fun onCleared() {
        super.onCleared()
        stopRecording()        // save the in-progress ride if the app is closed mid-session
        session.disconnect()
        wifiManager.disconnect()
        // Streaming can't continue once the ViewModel is gone, so make sure the foreground
        // service + wake/wifi locks + GPS don't outlive it (otherwise they'd hold the CPU/WiFi
        // awake with nothing producing frames).
        releaseBackgroundResources()
        voice.shutdown()
        com.example.northstar.data.RideDiagnostics.stop("app closed")
    }
}
