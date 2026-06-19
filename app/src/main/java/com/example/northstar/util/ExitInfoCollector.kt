package com.example.northstar.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.northstar.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures WHY the process last died — including the deaths [CrashGuard] can't see.
 *
 * CrashGuard only catches uncaught JVM exceptions. A native SIGSEGV (MapLibre, MediaCodec), an
 * ANR, or an OEM low-memory kill bypass it entirely, which is why those crashes kept landing with
 * no trace. Android's [ApplicationExitInfo] (API 30+) records the reason for each recent process
 * exit — and for native crashes / ANRs it carries the tombstone/trace. On the next launch we read
 * any NEW abnormal exits and write them to a diag/crash-*.log, which [com.example.northstar.data.
 * DiagnosticsUploader] then ships to Firestore — so a native crash is finally visible in pull-diag,
 * not just the Crashlytics console.
 */
object ExitInfoCollector {

    private const val TAG = "ExitInfoCollector"
    private const val PREFS = "northstar_exitinfo"
    private const val KEY_LAST_TS = "last_exit_ts"

    fun collect(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastSeen = prefs.getLong(KEY_LAST_TS, 0L)
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            val dir = File(context.getExternalFilesDir(null), "diag").apply { mkdirs() }
            var newest = lastSeen

            for (info in infos) {
                if (info.timestamp <= lastSeen) continue
                newest = maxOf(newest, info.timestamp)
                // Only the abnormal exits — a clean user-initiated close isn't a crash.
                if (info.reason !in INTERESTING) continue
                runCatching { writeExit(context, dir, info) }
                    .onFailure { Log.w(TAG, "write exit failed: ${it.message}") }
            }
            prefs.edit().putLong(KEY_LAST_TS, newest).apply()
        }.onFailure { Log.w(TAG, "collect failed: ${it.message}") }
    }

    private val INTERESTING = setOf(
        ApplicationExitInfo.REASON_CRASH,          // JVM crash (CrashGuard also catches; harmless dup)
        ApplicationExitInfo.REASON_CRASH_NATIVE,   // native SIGSEGV — the one we were blind to
        ApplicationExitInfo.REASON_ANR,
        ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_LOW_MEMORY,     // OEM killed us under pressure
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
    )

    private fun writeExit(context: Context, dir: File, info: ApplicationExitInfo) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(info.timestamp))
        // ANR + native crashes expose a trace/tombstone; other reasons don't.
        val trace = runCatching {
            info.traceInputStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()

        val text = buildString {
            appendLine("=== Northstar process exit (ApplicationExitInfo) ===")
            appendLine("when=$stamp (${info.timestamp})")
            appendLine("exception=${reasonName(info.reason)} — ${info.description ?: ""}")
            appendLine("importance=${info.importance} status=${info.status}")
            appendLine("apk=${BuildId.sha12(context)}")
            appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            if (trace != null) {
                appendLine()
                appendLine("--- trace / tombstone ---")
                append(trace)
            }
        }
        File(dir, "crash-exit-$stamp.log").writeText(text)
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "JVM_CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY_KILL"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        else -> "REASON_$reason"
    }
}
