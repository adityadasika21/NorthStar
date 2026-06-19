package com.example.northstar

import android.app.Application
import com.example.northstar.data.DiagnosticsUploader
import com.example.northstar.data.FirebaseFeatures
import com.example.northstar.util.BuildId
import com.example.northstar.util.CrashGuard

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
        // Flush any pending crash + ride logs to Firestore now that the app is open and likely has
        // internet. Crash logs upload on every build; ride logs are test-channel only.
        DiagnosticsUploader.uploadPending(this)
    }
}
