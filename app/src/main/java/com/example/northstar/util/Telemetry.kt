package com.example.northstar.util

import android.os.Bundle
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics

/**
 * Thin, always-safe wrapper over Firebase Analytics + Crashlytics.
 *
 * Two jobs:
 *   • Usage — [logScreen] / [logEvent] feed Analytics so we can see which features riders
 *     actually use and for how long (engagement/session time is auto-collected by the SDK;
 *     screen + feature events are what's added here).
 *   • Traceability — [recordNonFatal] / [breadcrumb] push the errors we'd otherwise swallow
 *     (FGS start failures, unresolved OEM intents, caught exceptions) into Crashlytics as
 *     non-fatals, so "no crash is untraced" covers the handled-but-bad paths too. Uncaught
 *     fatals are captured separately by [CrashGuard] + Crashlytics' own handler.
 *
 * Every call is wrapped in runCatching: when no Firebase project is configured the analytics/
 * crashlytics accessors throw, and we no-op — the app stays fully local, exactly like Auth.
 */
object Telemetry {

    private const val TAG = "Telemetry"

    /** Log a screen view (Analytics `screen_view`). [name] is the nav route. */
    fun logScreen(name: String) = runCatching {
        Firebase.analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, name)
        })
    }.onFailure { Log.v(TAG, "logScreen no-op: ${it.message}") }.let { Unit }

    /** Log a custom usage event with optional params (String/Long/Int/Double/Boolean). */
    fun logEvent(name: String, vararg params: Pair<String, Any?>) = runCatching {
        val b = Bundle()
        for ((k, v) in params) when (v) {
            null -> {}
            is String -> b.putString(k, v)
            is Int -> b.putLong(k, v.toLong())
            is Long -> b.putLong(k, v)
            is Float -> b.putDouble(k, v.toDouble())
            is Double -> b.putDouble(k, v)
            is Boolean -> b.putLong(k, if (v) 1 else 0)
            else -> b.putString(k, v.toString())
        }
        Firebase.analytics.logEvent(name, b)
    }.onFailure { Log.v(TAG, "logEvent no-op: ${it.message}") }.let { Unit }

    /** Record a handled exception as a Crashlytics non-fatal so it's never silently swallowed. */
    fun recordNonFatal(t: Throwable, where: String? = null) = runCatching {
        if (where != null) Firebase.crashlytics.log(where)
        Firebase.crashlytics.recordException(t)
    }.onFailure { Log.v(TAG, "recordNonFatal no-op: ${it.message}") }.let { Unit }

    /** Leave a Crashlytics breadcrumb that shows up attached to the next crash report. */
    fun breadcrumb(msg: String) = runCatching {
        Firebase.crashlytics.log(msg)
    }.onFailure { }.let { Unit }
}
