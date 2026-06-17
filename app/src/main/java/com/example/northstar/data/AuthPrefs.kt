package com.example.northstar.data

import android.content.Context

/**
 * Remembers that the rider has already passed the sign-in screen — either by signing in
 * or by choosing "Continue without signing in". Once set, the app opens straight to Home
 * on every launch instead of showing the login screen again. Cleared on sign-out so the
 * login screen returns.
 */
object AuthPrefs {
    private const val PREFS = "auth_prefs"
    private const val KEY_ONBOARDED = "onboarded"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once the rider has signed in or chosen to continue locally. */
    fun isOnboarded(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, value).apply()
    }
}
