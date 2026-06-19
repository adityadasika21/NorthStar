package com.example.northstar.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.northstar.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last-resort crash trace. Crashlytics already captures uncaught exceptions and ships them to
 * its console — but that console isn't reachable from the dev loop here, and a crash that kills
 * the process before Crashlytics flushes can be lost. So on top of Crashlytics we write the
 * fatal stack trace synchronously to a local `diag/crash-*.log` file, which the existing
 * [com.example.northstar.data.DiagnosticsUploader] flushes to Firestore on the next launch —
 * the same channel ride logs already use, readable via `tools/firebase/pull-diag.mjs`.
 *
 * Installed AFTER Firebase init so the previous handler in the chain is Crashlytics': we write
 * our file first, then delegate to it, so the crash still lands in the Crashlytics console too.
 */
object CrashGuard {

    private const val TAG = "CrashGuard"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Best-effort, fully synchronous — the process is about to die.
            runCatching { writeCrash(app, thread, error) }
                .onFailure { Log.w(TAG, "failed to persist crash: ${it.message}") }
            // Hand off to Crashlytics (and, ultimately, the system) so nothing else changes.
            previous?.uncaughtException(thread, error)
        }
        Log.i(TAG, "Uncaught-exception trace installed")
    }

    private fun writeCrash(context: Context, thread: Thread, error: Throwable) {
        val dir = File(context.getExternalFilesDir(null), "diag").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))

        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("=== Northstar crash ===")
            appendLine("when=$stamp ($now)")
            appendLine("thread=${thread.name}")
            appendLine("exception=${error.javaClass.name}: ${error.message}")
            appendLine("apk=${BuildId.sha12(context)}")
            appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine()
            append(stack)
        }
        File(dir, "crash-$stamp.log").writeText(text)
    }
}
