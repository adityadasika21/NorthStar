package com.example.northstar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.AuthViewModel
import com.example.northstar.viewmodel.ConnectionState

@Composable
fun SettingsScreen(
    conn: ConnectionState,
    onConnChange: (ConnectionState) -> Unit,
    authViewModel: AuthViewModel,
    onSignedOut: () -> Unit,
    onBack: () -> Unit,
) {
    var autoConnect by remember { mutableStateOf(true) }
    var screenOff   by remember { mutableStateOf(true) }
    var keepAwake   by remember { mutableStateOf(true) }
    var units       by remember { mutableStateOf("Kilometres") }
    var voice       by remember { mutableStateOf("Chime") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)

        // Account card
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 16.dp, onClick = {}) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(GoldTint),
                ) {
                    Text("AK", color = Gold, fontFamily = GeistMonoFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("arjun.k@gmail.com", color = TextHi, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
                        Icon(NorthstarIcons.Sync, contentDescription = null, tint = Gold, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Synced · 2 devices", color = TextMid, fontSize = 12.sp)
                    }
                }
                Icon(NorthstarIcons.ChevronRight, contentDescription = null, tint = TextLo, modifier = Modifier.size(18.dp))
            }
        }

        SectionLabel("Connection")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(NorthstarIcons.Bt, "Tripper Dash",
                sub = if (conn == ConnectionState.Connected) "Paired · RE-HIM-450" else "Not connected",
                control = { NorthstarChip(if (conn == ConnectionState.Connected) "Linked" else "Off", if (conn == ConnectionState.Connected) ChipTone.Gold else ChipTone.Off, dot = true) })
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(NorthstarIcons.Sync, "Auto-connect on start", "Link when the bike is near",
                control = { NorthstarToggle(autoConnect) { autoConnect = it } })
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(NorthstarIcons.Zap, "Stream quality", "Balanced · saves battery",
                control = { Icon(NorthstarIcons.ChevronRight, null, tint = TextLo, modifier = Modifier.size(18.dp)) }, last = true)
        }

        SectionLabel("During a ride")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            SettingRow(NorthstarIcons.Power, "Turn phone screen off", "Map keeps streaming to the dash",
                control = { NorthstarToggle(screenOff) { screenOff = it } })
            NorthstarDivider(Modifier.padding(horizontal = 6.dp))
            SettingRow(NorthstarIcons.Dash, "Keep dash awake", "Prevent Tripper sleep",
                control = { NorthstarToggle(keepAwake) { keepAwake = it } }, last = true)
        }

        SectionLabel("Voice & guidance")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            NorthstarSegmented(listOf("Off", "Chime", "Full TTS"), voice, { voice = it }, Modifier.fillMaxWidth())
        }

        SectionLabel("Units")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            NorthstarSegmented(listOf("Kilometres", "Miles"), units, { units = it }, Modifier.fillMaxWidth())
        }

        SectionLabel("Connection status (demo)")
        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 14.dp) {
            NorthstarSegmented(
                listOf("Connected", "Searching", "Offline"),
                selected = when (conn) { ConnectionState.Connected -> "Connected"; ConnectionState.Searching -> "Searching"; ConnectionState.Offline -> "Offline" },
                onSelect = { s ->
                    onConnChange(when (s) { "Connected" -> ConnectionState.Connected; "Searching" -> ConnectionState.Searching; else -> ConnectionState.Offline })
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(22.dp))

        NorthstarBtn(
            "Sign out",
            onClick = { authViewModel.signOut(); onSignedOut() },
            icon = NorthstarIcons.Power,
            variant = BtnVariant.Danger,
            size = BtnSize.Md,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(6.dp))

        Text(
            "NORTHSTAR v1.0 · Firebase sync",
            color = TextDis, fontSize = 11.sp, fontFamily = GeistMonoFamily,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp),
        )
    }
}

@Composable
private fun SectionLabel(label: String) {
    Eyebrow(label, Modifier.padding(top = 22.dp, bottom = 9.dp, start = 4.dp))
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    sub: String? = null,
    control: @Composable () -> Unit,
    last: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 13.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(11.dp)),
        ) {
            Icon(icon, contentDescription = null, tint = TextMid, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
            if (sub != null) Text(sub, color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        }
        control()
    }
}
