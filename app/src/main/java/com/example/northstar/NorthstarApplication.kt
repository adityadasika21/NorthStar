package com.example.northstar

import android.app.Application
import com.example.northstar.data.DiagnosticsUploader
import com.example.northstar.data.FirebaseFeatures
import com.example.northstar.util.BuildId

class NorthstarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Hash the installed APK off-thread so the build identity is ready to stamp into ride logs.
        BuildId.warm(this)
        // Firebase is auto-initialized from google-services.json via the Google Services plugin.
        // Turn on the rest of the product suite (Analytics, Crashlytics, Performance, Remote
        // Config, App Check). No-op when no Firebase project is configured — the app stays local.
        FirebaseFeatures.init(this)
        // Flush any ride/connection logs from a previous (possibly out-of-range) ride to Firestore
        // now that the app is open and likely has internet. Test-channel + Firebase-gated no-op.
        DiagnosticsUploader.uploadPending(this)
    }
}
