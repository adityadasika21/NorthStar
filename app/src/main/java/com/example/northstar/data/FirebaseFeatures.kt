package com.example.northstar.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.northstar.BuildConfig
import com.example.northstar.util.DeviceId
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.perf.performance
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings

/**
 * One-shot initializer for the full Firebase product suite (Analytics, Crashlytics,
 * Performance, Remote Config, App Check; Cloud Messaging wires itself up via the
 * manifest-declared service).
 *
 * Everything here is gated behind [FirebaseGate.isConfigured]: with no google-services.json
 * the default FirebaseApp never initializes, so [init] is a no-op and the app runs fully
 * local — exactly like Auth/Firestore already do. Call once from
 * [com.example.northstar.NorthstarApplication.onCreate].
 */
object FirebaseFeatures {

    private const val TAG = "FirebaseFeatures"

    /** Remote Config keys. Add flags here as features grow; defaults live in [remoteConfigDefaults]. */
    object Flags {
        const val FORCE_UPDATE_VERSION_CODE = "force_update_version_code"
        const val DASH_RECONNECT_ENABLED = "dash_reconnect_enabled"
    }

    private val remoteConfigDefaults: Map<String, Any> = mapOf(
        Flags.FORCE_UPDATE_VERSION_CODE to 0L,
        Flags.DASH_RECONNECT_ENABLED to true,
    )

    fun init(context: Context) {
        if (!FirebaseGate.isConfigured(context)) {
            Log.i(TAG, "No Firebase project configured — skipping Firebase suite init (running local).")
            return
        }

        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        initAppCheck(context, debuggable)
        initCrashlytics(context, debuggable)
        initAnalytics(context, debuggable)
        initPerformance()
        initRemoteConfig(debuggable)
    }

    /**
     * App Check attests the app to Firebase backends. Production uses Play Integrity; debug
     * builds use the debug provider (only present via debugImplementation), reached through
     * reflection so the release classpath stays clean.
     */
    private fun initAppCheck(context: Context, debuggable: Boolean) = runCatching {
        val appCheck = Firebase.appCheck
        if (debuggable) {
            val factory = Class.forName(
                "com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory"
            ).getMethod("getInstance").invoke(null)
            appCheck.installAppCheckProviderFactory(
                factory as com.google.firebase.appcheck.AppCheckProviderFactory
            )
        } else {
            appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }
    }.onFailure { Log.w(TAG, "App Check init failed", it) }

    private fun initCrashlytics(context: Context, debuggable: Boolean) = runCatching {
        // Collect crashes everywhere; tag the build flavour so debug noise is filterable, and key
        // the user to the diagnostics deviceId so a crash maps to a pull-diag device.
        Firebase.crashlytics.apply {
            setCrashlyticsCollectionEnabled(true)
            setCustomKey("build_debuggable", debuggable)
            setUserId(DeviceId.get(context))
        }
    }.onFailure { Log.w(TAG, "Crashlytics init failed", it) }

    private fun initAnalytics(context: Context, debuggable: Boolean) = runCatching {
        Firebase.analytics.apply {
            setAnalyticsCollectionEnabled(true)
            // Debug and release share one Analytics property (same applicationId), so DAU/usage
            // mixes my test installs with real users' release installs. These user properties make
            // every metric splittable: build_type debug|release, app_channel test|prod. ns_device_id
            // ties an Analytics user back to the pull-diag deviceId for cross-checking.
            setUserProperty("build_type", if (debuggable) "debug" else "release")
            setUserProperty("app_channel", if (BuildConfig.DIAG_UPLOAD) "test" else "prod")
            setUserProperty("ns_device_id", DeviceId.get(context))
        }
    }.onFailure { Log.w(TAG, "Analytics init failed", it) }

    private fun initPerformance() = runCatching {
        Firebase.performance.isPerformanceCollectionEnabled = true
    }.onFailure { Log.w(TAG, "Performance init failed", it) }

    private fun initRemoteConfig(debuggable: Boolean) = runCatching {
        Firebase.remoteConfig.apply {
            setConfigSettingsAsync(
                remoteConfigSettings {
                    // Tight fetch interval on debug for testing; conservative on release.
                    minimumFetchIntervalInSeconds = if (debuggable) 0 else 3600
                }
            )
            setDefaultsAsync(remoteConfigDefaults)
            fetchAndActivate().addOnFailureListener { e ->
                Log.w(TAG, "Remote Config fetch failed", e)
            }
        }
    }.onFailure { Log.w(TAG, "Remote Config init failed", it) }
}
