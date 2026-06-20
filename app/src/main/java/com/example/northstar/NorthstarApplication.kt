package com.example.northstar

import android.app.Application
import com.example.northstar.data.DiagnosticsUploader
import com.example.northstar.data.FirebaseFeatures
import com.example.northstar.util.BuildId
import com.example.northstar.util.CrashGuard
import com.example.northstar.util.ExitInfoCollector

class NorthstarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Hash the installed APK off-thread so the build identity is ready to stamp into ride logs.
        BuildId.warm(this)
        // Firebase is auto-initialized from google-services.json via the Google Services plugin.
        // Turn on the rest of the product suite (Analytics, Crashlytics, Performance, Remote
        // Config, App Check). No-op when no Firebase project is configured — the app stays local.
        FirebaseFeatures.init(this)
        // Install our uncaught-exception trace AFTER Firebase init so it chains to Crashlytics'
        // handler: every fatal lands in Crashlytics AND in a local crash log that uploads to
        // Firestore on the next launch — no crash goes untraced, even off the Crashlytics console.
        CrashGuard.install(this)
        // Capture WHY we died last time — native crashes, ANRs, OEM kills — which CrashGuard's JVM
        // handler can't see. Writes a crash-exit-*.log for any new abnormal exit, picked up below.
        // arm() tags this process with its build sha so the NEXT crash is attributed to the build
        // that actually ran, not whatever is installed when we read it back; collect() reads prior exits.
        ExitInfoCollector.arm(this)
        ExitInfoCollector.collect(this)
        // Flush any pending crash + ride logs to Firestore now that the app is open and likely has
        // internet. Crash logs upload on every build; ride logs are test-channel only.
        DiagnosticsUploader.uploadPending(this)
        // Reinstalling an APK leaves the NotificationListenerService UNBOUND even though the grant
        // persists — which silently breaks now-playing + call mirroring. Ask the system to rebind it
        // on every launch so an over-the-top install self-heals without the user re-toggling access.
        rebindNotificationListener()
    }

    private fun rebindNotificationListener() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) return
        runCatching {
            if (com.example.northstar.media.MediaInfoProvider.isAccessGranted(this)) {
                android.service.notification.NotificationListenerService.requestRebind(
                    android.content.ComponentName(this, com.example.northstar.media.NorthstarNotificationListener::class.java)
                )
            }
        }
    }
}
