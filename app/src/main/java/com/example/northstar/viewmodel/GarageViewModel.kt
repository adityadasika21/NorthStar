package com.example.northstar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.data.FuelFillup
import com.example.northstar.data.MaintenanceItem
import com.example.northstar.data.NorthstarDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FuelRow(val fill: FuelFillup, val kmpl: Double?)
data class MaintRow(val item: MaintenanceItem, val remainingKm: Int, val tone: String)

data class GarageUi(
    val odometerKm: Int = 0,
    val fuel: List<FuelRow> = emptyList(),     // newest first; kmpl vs the prior fill
    val maint: List<MaintRow> = emptyList(),
    val avgKmpl30: Double? = null,
    val spent30: Double = 0.0,
    val litres30: Double = 0.0,
    val fills30: Int = 0,
)

class GarageViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NorthstarDb.get(app)
    private val _ui = MutableStateFlow(GarageUi())
    val ui = _ui.asStateFlow()

    init { reload() }

    private fun reload() = viewModelScope.launch {
        _ui.value = withContext(Dispatchers.IO) { compute() }
    }

    private fun compute(): GarageUi {
        val odo = db.odometer()
        val fills = db.fuelFills()   // highest odometer (newest) first
        val fuelRows = fills.mapIndexed { i, f ->
            val prev = fills.getOrNull(i + 1)   // next-lower odometer fill
            val kmpl = if (prev != null && f.litres > 0 && f.odometerKm > prev.odometerKm)
                (f.odometerKm - prev.odometerKm) / f.litres else null
            FuelRow(f, kmpl)
        }
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val recent = fuelRows.filter { it.fill.dateMs >= cutoff }
        val kmpls = recent.mapNotNull { it.kmpl }
        val maint = db.maintenanceItems().map { m ->
            val remaining = m.lastDoneOdoKm + m.intervalKm - odo
            val tone = when {
                remaining < 0 -> "alert"
                remaining < m.intervalKm * 0.25 -> "warn"
                else -> "ok"
            }
            MaintRow(m, remaining, tone)
        }
        return GarageUi(
            odometerKm = odo,
            fuel = fuelRows,
            maint = maint,
            avgKmpl30 = kmpls.takeIf { it.isNotEmpty() }?.average(),
            spent30 = recent.sumOf { it.fill.cost },
            litres30 = recent.sumOf { it.fill.litres },
            fills30 = recent.size,
        )
    }

    fun addFuel(litres: Double, cost: Double, odometerKm: Int, location: String) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            db.addFuel(FuelFillup(dateMs = System.currentTimeMillis(), litres = litres, cost = cost, odometerKm = odometerKm, location = location))
        }
        reload()
    }

    fun deleteFuel(id: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { db.deleteFuel(id) }; reload()
    }

    fun markServiceDone(id: Long, odoKm: Int) = viewModelScope.launch {
        withContext(Dispatchers.IO) { db.markServiceDone(id, odoKm) }; reload()
    }

    fun addService(name: String, iconKey: String, intervalKm: Int) = viewModelScope.launch {
        withContext(Dispatchers.IO) { db.addMaintenance(name, iconKey, intervalKm, db.odometer()) }; reload()
    }

    fun deleteService(id: Long) = viewModelScope.launch {
        withContext(Dispatchers.IO) { db.deleteMaintenance(id) }; reload()
    }

    fun setOdometer(km: Int) = viewModelScope.launch {
        withContext(Dispatchers.IO) { db.setOdometer(km) }; reload()
    }
}
