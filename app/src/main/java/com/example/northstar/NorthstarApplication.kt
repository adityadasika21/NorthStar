package com.example.northstar

import android.app.Application
import com.example.northstar.data.FirebaseFeatures

class NorthstarApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Firebase is auto-initialized from google-services.json via the Google Services plugin.
        // Turn on the rest of the product suite (Analytics, Crashlytics, Performance, Remote
        // Config, App Check). No-op when no Firebase project is configured — the app stays local.
        FirebaseFeatures.init(this)
    }
}
