package com.example.opendash.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.opendash.ui.OpenDashIcons
import com.example.opendash.ui.components.BtnSize
import com.example.opendash.ui.components.BtnVariant
import com.example.opendash.ui.components.OpenDashBtn
import com.example.opendash.ui.components.OpenDashCard
import com.example.opendash.ui.components.OpenDashDivider
import com.example.opendash.ui.components.OpenDashIconBtn
import com.example.opendash.ui.components.ScreenHeader
import com.example.opendash.ui.theme.Alert
import com.example.opendash.ui.theme.GeistFamily
import com.example.opendash.ui.theme.Gold
import com.example.opendash.ui.theme.TextHi
import com.example.opendash.ui.theme.TextLo
import com.example.opendash.ui.theme.TextMid

private data class VehicleProfile(
    val title: String,
    val nickname: String,
    val puc: String,
    val insurance: String,
    val service: String,
)

@Composable
fun VehiclesScreen() {
    var vehicles by remember {
        mutableStateOf(
            listOf(
                VehicleProfile(
                    title = "Himalayan 450",
                    nickname = "Default vehicle",
                    puc = "Not set",
                    insurance = "Not set",
                    service = "Not set",
                ),
            ),
        )
    }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var addingVehicle by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(title = "Vehicles")

        SectionTitle("My Vehicles")
        OpenDashCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp) {
            vehicles.forEachIndexed { index, vehicle ->
                if (index > 0) OpenDashDivider(Modifier.padding(vertical = 14.dp))
                VehicleBlock(
                    vehicle = vehicle,
                    onEdit = { editingIndex = index },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        OpenDashBtn(
            "Add vehicle",
            onClick = { addingVehicle = true },
            icon = OpenDashIcons.Plus,
            variant = BtnVariant.Primary,
            size = BtnSize.Md,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    editingIndex?.let { index ->
        EditVehicleDialog(
            dialogTitle = "Edit vehicle",
            vehicle = vehicles[index],
            onDismiss = { editingIndex = null },
            onSave = { updated ->
                vehicles = vehicles.toMutableList().also { it[index] = updated }
                editingIndex = null
            },
        )
    }

    if (addingVehicle) {
        EditVehicleDialog(
            dialogTitle = "Add vehicle",
            vehicle = VehicleProfile(
                title = "",
                nickname = "",
                puc = "Not set",
                insurance = "Not set",
                service = "Not set",
            ),
            onDismiss = { addingVehicle = false },
            onSave = { updated ->
                vehicles = vehicles + updated
                addingVehicle = false
            },
        )
    }
}

@Composable
private fun SectionTitle(label: String) {
    Text(
        label,
        color = TextHi,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = GeistFamily,
        modifier = Modifier.padding(top = 22.dp, bottom = 10.dp, start = 2.dp),
    )
}

@Composable
private fun VehicleBlock(vehicle: VehicleProfile, onEdit: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(OpenDashIcons.Motor, contentDescription = null, tint = TextMid, modifier = Modifier.size(30.dp).padding(top = 5.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(vehicle.title, color = Gold, fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (vehicle.nickname.isNotBlank()) Text(vehicle.nickname, color = TextMid, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(14.dp))
            VehicleMeta("PUC", vehicle.puc, alert = vehicle.puc.isProblemValue())
            VehicleMeta("Insurance", vehicle.insurance, alert = vehicle.insurance.isProblemValue())
            VehicleMeta("Service", vehicle.service)
        }
        OpenDashIconBtn(OpenDashIcons.Edit, onClick = onEdit, size = 34.dp)
    }
}

@Composable
private fun VehicleMeta(label: String, value: String, alert: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = TextLo, fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Text(":", color = TextLo, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Text(value, color = if (alert) Alert else TextMid, fontSize = 13.sp)
    }
}

@Composable
private fun EditVehicleDialog(
    dialogTitle: String,
    vehicle: VehicleProfile,
    onSave: (VehicleProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember(vehicle) { mutableStateOf(vehicle.title) }
    var nickname by remember(vehicle) { mutableStateOf(vehicle.nickname) }
    val initialPuc = remember(vehicle) { vehicle.puc.toVehicleDateParts() }
    val initialInsurance = remember(vehicle) { vehicle.insurance.toVehicleDateParts() }
    var pucDay by remember(vehicle) { mutableStateOf(initialPuc.day) }
    var pucMonth by remember(vehicle) { mutableStateOf(initialPuc.month) }
    var pucYear by remember(vehicle) { mutableStateOf(initialPuc.year) }
    var insuranceDay by remember(vehicle) { mutableStateOf(initialInsurance.day) }
    var insuranceMonth by remember(vehicle) { mutableStateOf(initialInsurance.month) }
    var insuranceYear by remember(vehicle) { mutableStateOf(initialInsurance.year) }
    var service by remember(vehicle) { mutableStateOf(vehicle.service) }
    val valid = title.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialogTitle, color = TextHi) },
        text = {
            Column {
                VehicleTextField(title, { title = it }, "Vehicle name")
                VehicleTextField(nickname, { nickname = it }, "Nickname")
                VehicleDateFields(
                    label = "PUC expiry",
                    day = pucDay,
                    month = pucMonth,
                    year = pucYear,
                    onDay = { pucDay = it.filter { ch -> ch.isDigit() }.take(2) },
                    onMonth = { pucMonth = it.take(3) },
                    onYear = { pucYear = it.filter { ch -> ch.isDigit() }.take(4) },
                )
                VehicleDateFields(
                    label = "Insurance expiry",
                    day = insuranceDay,
                    month = insuranceMonth,
                    year = insuranceYear,
                    onDay = { insuranceDay = it.filter { ch -> ch.isDigit() }.take(2) },
                    onMonth = { insuranceMonth = it.take(3) },
                    onYear = { insuranceYear = it.filter { ch -> ch.isDigit() }.take(4) },
                )
                VehicleTextField(service, { service = it }, "Service")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        VehicleProfile(
                            title = title.trim(),
                            nickname = nickname.trim(),
                            puc = formatVehicleDate(pucDay, pucMonth, pucYear),
                            insurance = formatVehicleDate(insuranceDay, insuranceMonth, insuranceYear),
                            service = service.trim().ifBlank { "Not set" },
                        ),
                    )
                },
            ) {
                Text("Save", color = if (valid) Gold else TextLo)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMid) }
        },
    )
}

@Composable
private fun VehicleDateFields(
    label: String,
    day: String,
    month: String,
    year: String,
    onDay: (String) -> Unit,
    onMonth: (String) -> Unit,
    onYear: (String) -> Unit,
) {
    Text(label, color = TextMid, fontSize = 12.5.sp, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        VehicleTextField(day, onDay, "DD", Modifier.weight(0.8f), KeyboardType.Number)
        VehicleTextField(month, onMonth, "MMM", Modifier.weight(1.1f), KeyboardType.Text)
        VehicleTextField(year, onYear, "YYYY", Modifier.weight(1.1f), KeyboardType.Number)
    }
}

@Composable
private fun VehicleTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.padding(top = 8.dp),
    )
}

private fun String.isProblemValue(): Boolean =
    equals("expired", ignoreCase = true) || equals("na", ignoreCase = true)

private data class VehicleDateParts(val day: String, val month: String, val year: String)

private val vehicleMonths = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private fun String.toVehicleDateParts(): VehicleDateParts {
    val match = Regex("""(\d{1,2})-([A-Za-z]{3})-(\d{4})""").find(this.trim())
    if (match != null) {
        val (day, month, year) = match.destructured
        return VehicleDateParts(day.padStart(2, '0'), month.replaceFirstChar { it.uppercase() }, year)
    }
    return VehicleDateParts("01", "Jan", "2030")
}

private fun formatVehicleDate(day: String, month: String, year: String): String {
    val cleanDay = day.toIntOrNull()?.coerceIn(1, 31)?.toString()?.padStart(2, '0') ?: "01"
    val cleanMonth = vehicleMonths.firstOrNull { it.equals(month.trim(), ignoreCase = true) }
        ?: vehicleMonths.first()
    val cleanYear = year.toIntOrNull()?.coerceIn(2024, 2099)?.toString() ?: "2030"
    return "$cleanDay-$cleanMonth-$cleanYear"
}
