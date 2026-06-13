package com.example.northstar.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-device SQLite — the source of truth for the Garage (maintenance + fuel) and the
 * bike's odometer. Plain SQLiteOpenHelper (no Room/KSP) keeps the build dependency-free.
 * All calls here are synchronous; callers run them off the main thread.
 */
class NorthstarDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "northstar.db", null, 1) {

    companion object {
        @Volatile private var instance: NorthstarDb? = null
        fun get(context: Context): NorthstarDb =
            instance ?: synchronized(this) {
                instance ?: NorthstarDb(context).also { instance = it }
            }
        const val DEFAULT_ODOMETER = 325   // seeded from the bike's current ODO; user-editable
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE fuel_fillup(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 date_ms INTEGER NOT NULL,
                 litres REAL NOT NULL,
                 cost REAL NOT NULL,
                 odometer_km INTEGER NOT NULL,
                 location TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL(
            """CREATE TABLE maintenance_item(
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 name TEXT NOT NULL,
                 icon_key TEXT NOT NULL,
                 interval_km INTEGER NOT NULL,
                 last_done_odo_km INTEGER NOT NULL,
                 last_done_date_ms INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE TABLE bike_state(id INTEGER PRIMARY KEY, odometer_km INTEGER NOT NULL)")
        db.execSQL("INSERT INTO bike_state(id, odometer_km) VALUES (0, $DEFAULT_ODOMETER)")
        seedMaintenance(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { /* v1 only */ }

    private fun seedMaintenance(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        // name, icon, intervalKm
        val seeds = listOf(
            Triple("Chain clean & lube", "chain", 500),
            Triple("Engine oil", "drop", 5000),
            Triple("Air filter", "wrench", 8000),
            Triple("Brake pads (front)", "gauge", 6000),
            Triple("Coolant", "thermo", 12000),
        )
        for ((name, icon, interval) in seeds) {
            db.insert("maintenance_item", null, ContentValues().apply {
                put("name", name)
                put("icon_key", icon)
                put("interval_km", interval)
                put("last_done_odo_km", 0)
                put("last_done_date_ms", now)
            })
        }
    }

    // ── Odometer ──────────────────────────────────────────────────────────
    fun odometer(): Int =
        readableDatabase.rawQuery("SELECT odometer_km FROM bike_state WHERE id=0", null).use {
            if (it.moveToFirst()) it.getInt(0) else DEFAULT_ODOMETER
        }

    fun setOdometer(km: Int) {
        writableDatabase.update(
            "bike_state", ContentValues().apply { put("odometer_km", km) }, "id=0", null,
        )
    }

    // ── Fuel ──────────────────────────────────────────────────────────────
    /** Newest first. */
    fun fuelFills(): List<FuelFillup> {
        val out = ArrayList<FuelFillup>()
        readableDatabase.rawQuery(
            "SELECT id,date_ms,litres,cost,odometer_km,location FROM fuel_fillup " +
                "ORDER BY odometer_km DESC, date_ms DESC", null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    FuelFillup(
                        id = c.getLong(0), dateMs = c.getLong(1), litres = c.getDouble(2),
                        cost = c.getDouble(3), odometerKm = c.getInt(4), location = c.getString(5) ?: "",
                    )
                )
            }
        }
        return out
    }

    fun addFuel(fill: FuelFillup) {
        writableDatabase.insert("fuel_fillup", null, ContentValues().apply {
            put("date_ms", fill.dateMs)
            put("litres", fill.litres)
            put("cost", fill.cost)
            put("odometer_km", fill.odometerKm)
            put("location", fill.location)
        })
        // A fresh fill advances the odometer if it's the highest reading we've seen.
        if (fill.odometerKm > odometer()) setOdometer(fill.odometerKm)
    }

    fun deleteFuel(id: Long) {
        writableDatabase.delete("fuel_fillup", "id=?", arrayOf(id.toString()))
    }

    // ── Maintenance ───────────────────────────────────────────────────────
    fun maintenanceItems(): List<MaintenanceItem> {
        val out = ArrayList<MaintenanceItem>()
        readableDatabase.rawQuery(
            "SELECT id,name,icon_key,interval_km,last_done_odo_km,last_done_date_ms " +
                "FROM maintenance_item ORDER BY id ASC", null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    MaintenanceItem(
                        id = c.getLong(0), name = c.getString(1), iconKey = c.getString(2),
                        intervalKm = c.getInt(3), lastDoneOdoKm = c.getInt(4), lastDoneDateMs = c.getLong(5),
                    )
                )
            }
        }
        return out
    }

    /** Mark a service done at [odoKm] (defaults to the current odometer). */
    fun markServiceDone(id: Long, odoKm: Int, whenMs: Long = System.currentTimeMillis()) {
        writableDatabase.update(
            "maintenance_item",
            ContentValues().apply { put("last_done_odo_km", odoKm); put("last_done_date_ms", whenMs) },
            "id=?", arrayOf(id.toString()),
        )
    }

    fun addMaintenance(name: String, iconKey: String, intervalKm: Int, lastDoneOdoKm: Int) {
        writableDatabase.insert("maintenance_item", null, ContentValues().apply {
            put("name", name)
            put("icon_key", iconKey)
            put("interval_km", intervalKm)
            put("last_done_odo_km", lastDoneOdoKm)
            put("last_done_date_ms", System.currentTimeMillis())
        })
    }

    fun deleteMaintenance(id: Long) {
        writableDatabase.delete("maintenance_item", "id=?", arrayOf(id.toString()))
    }
}
