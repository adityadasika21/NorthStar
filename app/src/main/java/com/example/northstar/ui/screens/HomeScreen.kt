package com.example.northstar.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.ConnectionState

@Composable
fun HomeScreen(
    conn: ConnectionState,
    onNavigate: (String) -> Unit,
) {
    val status = when (conn) {
        ConnectionState.Connected -> Triple("Connected", "Streaming to Tripper Dash", Gold)
        ConnectionState.Searching -> Triple("Searching…", "Looking for Tripper Dash", Warn)
        ConnectionState.Offline   -> Triple("Offline", "Dash not detected", Offline)
    }
    val (statusLabel, statusSub, statusDot) = status

    // Pulse animation for connected dot
    val infiniteTransition = rememberInfiniteTransition(label = "dot-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        1f, 0.35f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            wordmark = true,
            trailing = { NorthstarIconBtn(NorthstarIcons.Gear, onClick = { onNavigate("settings") }) },
        )

        // Connection hero card
        NorthstarCard(
            glow = conn == ConnectionState.Connected,
            padding = 20.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularDash(
                    size = 118.dp,
                    pan = Offset.Zero,
                    zoom = 1f,
                    compact = true,
                    live = conn == ConnectionState.Connected,
                )

                Spacer(Modifier.width(18.dp))

                Column(Modifier.weight(1f)) {
                    Eyebrow("Royal Enfield · Tripper")

                    Spacer(Modifier.height(7.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(9.dp).clip(CircleShape).background(
                                statusDot.copy(alpha = if (conn == ConnectionState.Connected) pulseAlpha else 1f)
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            statusLabel, color = TextHi, fontSize = 19.sp,
                            fontWeight = FontWeight.Bold, fontFamily = GeistFamily,
                            letterSpacing = (-0.38).sp,
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Text(statusSub, color = TextMid, fontSize = 13.sp)

                    Spacer(Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        NorthstarChip("RE-HIM-450", ChipTone.Gold, icon = NorthstarIcons.Bt)
                        if (conn == ConnectionState.Connected) {
                            NorthstarChip("2.4 GHz", ChipTone.Neutral)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Quick state tiles
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf(
                Triple(NorthstarIcons.Motor, "Bike", "Parked" to TextHi),
                Triple(NorthstarIcons.Target, "GPS", "Strong" to Gold),
                Triple(NorthstarIcons.Power, "Phone", "74%" to TextHi),
            ).forEach { (icon, label, valAndColor) ->
                val (value, color) = valAndColor
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Surf1)
                        .border(1.dp, Line, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                ) {
                    Icon(icon, contentDescription = label, tint = TextLo, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                    Spacer(Modifier.height(2.dp))
                    Eyebrow(label)
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        NorthstarBtn(
            "Start navigation", onClick = { onNavigate("route") },
            icon = NorthstarIcons.Navi, variant = BtnVariant.Primary, size = BtnSize.Lg,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))

        NorthstarBtn(
            "Open dash view", onClick = { onNavigate("dash") },
            icon = NorthstarIcons.Dash, variant = BtnVariant.Secondary, size = BtnSize.Md,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(18.dp))

        // Maintenance nudge
        NorthstarCard(
            modifier = Modifier.fillMaxWidth(),
            padding = 0.dp,
            onClick = { onNavigate("garage") },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Box(
                    Modifier
                        .width(3.dp).fillMaxHeight()
                        .background(Warn)
                )
                Spacer(Modifier.width(14.dp))
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Warn.copy(alpha = 0.13f)),
                ) {
                    Icon(NorthstarIcons.Chain, contentDescription = null, tint = Warn, modifier = Modifier.size(21.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Chain lube due soon", color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text("120 km since last · clean & lube", color = TextLo, fontSize = 12.5.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Icon(NorthstarIcons.ChevronRight, contentDescription = null, tint = TextLo, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(18.dp))

        Eyebrow("Recent destinations", Modifier.padding(bottom = 6.dp, start = 4.dp))

        NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
            NorthstarRow(
                "Chitkul Village", icon = NorthstarIcons.LocationPin,
                sub = "Himachal · 218 km", right = "4h 50m",
                trailingIcon = true, onClick = { onNavigate("route") },
            )
            NorthstarDivider(Modifier.padding(horizontal = 4.dp))
            NorthstarRow(
                "Jalori Pass", icon = NorthstarIcons.LocationPin,
                sub = "Himachal · 142 km", right = "3h 20m",
                trailingIcon = true, onClick = { onNavigate("route") },
            )
        }
    }
}
