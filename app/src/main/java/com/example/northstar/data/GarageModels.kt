package com.example.northstar.data

/** A single fuel fill-up. Mileage (km/l) is derived from the odometer gap to the prior fill. */
data class FuelFillup(
    val id: Long = 0,
    val dateMs: Long,
    val litres: Double,
    val cost: Double,
    val odometerKm: Int,
    val location: String = "",
)

/** A recurring maintenance item with its interval and when it was last serviced. */
data class MaintenanceItem(
    val id: Long = 0,
    val name: String,
    val iconKey: String,        // "chain" | "drop" | "wrench" | "gauge" | "thermo" | "fuel"
    val intervalKm: Int,
    val lastDoneOdoKm: Int,
    val lastDoneDateMs: Long,
)
