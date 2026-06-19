package com.example.northstar.data

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.northstar.BuildConfig
import com.example.northstar.util.DeviceId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Uploads finished [RideDiagnostics] session logs to Firestore so they can be retrieved
 * remotely — no USB/adb. The rider rides (possibly out of laptop range), comes home, opens
 * the app; the next time there's internet the pending logs land in the `diagnostics`
 * collection, where `tools/firebase/pull-diag.mjs` reads them back for debugging.
 *
 * Strictly opt-in and test-channel only:
 *   - no-op unless [BuildConfig.DIAG_UPLOAD] (flipped off for real public releases), and
 *   - no-op unless a Firebase project is configured ([FirebaseGate]).
 * Keyed by an anonymous per-install [DeviceId]; no account/sign-in required (rides happen
 * signed-out), and uploaded files are remembered so they're never re-sent.
 */
object DiagnosticsUploader {
    private const val TAG = "DiagnosticsUploader"
    private const val PREFS = "northstar_diag_upload"
    private const val KEY_DONE = "uploaded_files"
    private const val COLLECTION = "diagnostics"
    // Firestore caps a document at ~1 MiB; ride logs are a few KB, but truncate defensively.
    private const val MAX_CONTENT = 900_000

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Upload any not-yet-sent logs. Safe to call on every app start.
     *
     * Crash traces (`crash-*.log`) upload whenever Firebase is configured — even on public
     * releases — so a crash on anyone's phone is never untraced: it reaches both Crashlytics
     * and the `diagnostics` collection the dev loop can actually read. Ride logs (`ride-*.log`)
     * stay test-channel only ([BuildConfig.DIAG_UPLOAD] off in release).
     */
    fun uploadPending(context: Context) {
        if (!FirebaseGate.isConfigured(context)) return
        io.launch { runCatching { upload(context.applicationContext) }
            .onFailure { Log.w(TAG, "diag upload failed: ${it.message}") } }
    }

    private suspend fun upload(context: Context) {
        val dir = File(context.getExternalFilesDir(null), "diag")
        // Crash traces always; ride logs only on the test channel.
        val logs = dir.listFiles { f ->
            f.name.endsWith(".log") &&
                (f.name.startsWith("crash-") || (BuildConfig.DIAG_UPLOAD && f.name.startsWith("ride-")))
        }?.sortedBy { it.lastModified() } ?: return
        if (logs.isEmpty()) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val done = prefs.getStringSet(KEY_DONE, emptySet())!!.toMutableSet()
        // Don't re-upload the live ride file (still being appended to). Crash files are
        // written once at death, so they're always eligible.
        val openFile = logs.filter { it.name.startsWith("ride-") }.maxByOrNull { it.lastModified() }
        val fs = FirebaseFirestore.getInstance()
        val deviceId = DeviceId.get(context)

        for (f in logs) {
            if (f.name in done) continue
            if (f === openFile && System.currentTimeMillis() - f.lastModified() < 60_000) continue
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val content = if (text.length > MAX_CONTENT) text.takeLast(MAX_CONTENT) else text
            val isCrash = f.name.startsWith("crash-")
            val doc = mapOf(
                "kind" to if (isCrash) "crash" else "ride",
                "exception" to if (isCrash) crashSummary(content) else null,
                "deviceId" to deviceId,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "androidSdk" to Build.VERSION.SDK_INT,
                "androidRelease" to Build.VERSION.RELEASE,
                "appVersion" to BuildConfig.VERSION_NAME,
                "versionCode" to BuildConfig.VERSION_CODE,
                "session" to f.name,
                "content" to content,
                "uploadedAt" to FieldValue.serverTimestamp(),
            )
            // Deterministic id (device + session) so a re-upload overwrites rather than duplicates.
            val id = "${deviceId}__${f.name.removeSuffix(".log")}"
            runCatching {
                fs.collection(COLLECTION).document(id).set(doc).await()
            }.onSuccess {
                done.add(f.name)
                prefs.edit().putStringSet(KEY_DONE, done).apply()
                Log.i(TAG, "uploaded diag ${f.name}")
            }.onFailure {
                Log.w(TAG, "upload of ${f.name} failed: ${it.message}")
                return  // stop on first failure (likely offline) — retry next app start
            }
        }
    }

    /** One-line "exception=..." summary pulled from a crash log, for at-a-glance scanning. */
    private fun crashSummary(content: String): String =
        content.lineSequence()
            .firstOrNull { it.startsWith("exception=") }
            ?.removePrefix("exception=")
            ?.take(300)
            .orEmpty()
}
