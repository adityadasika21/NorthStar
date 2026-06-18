package com.example.northstar.util

import android.content.Context
import java.util.UUID

/**
 * Stable, anonymous per-install identifier. Used to key remote diagnostics so logs pulled
 * from Firestore can be grouped by phone (e.g. distinguishing OnePlus vs Samsung test
 * devices) without collecting anything personal. Generated once and persisted; survives
 * across launches, regenerates only on reinstall/clear-data.
 */
object DeviceId {
    private const val PREFS = "northstar_device"
    private const val KEY = "device_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }
        val id = "dev-" + UUID.randomUUID().toString().take(12)
        prefs.edit().putString(KEY, id).apply()
        return id
    }
}
