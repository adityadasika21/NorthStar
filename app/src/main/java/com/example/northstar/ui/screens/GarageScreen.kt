package com.example.northstar.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*

@Composable
fun GarageScreen(tab: String, onTabChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(eyebrow = "Himalayan 450 · 14,280 km", title = "Garage")

        NorthstarSegmented(
            options = listOf("Maintenance", "Fuel diary"),
            selected = tab,
            onSelect = onTabChange,
            modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
        )

        if (tab == "Maintenance") MaintenanceTab() else FuelTab()
    }
}

@Composable
private fun MaintenanceTab() {
    data class Service(val icon: androidx.compose.ui.graphics.vector.ImageVector, val name: String, val last: String, val leftKm: Int, val of: Int, val tone: String, val due: String)
    val services = listOf(
        Service(NorthstarIcons.Chain,  "Chain clean & lube",   "May 28",       120,   500,  "warn",  "in 120 km"),
        Service(NorthstarIcons.Drop,   "Engine oil",           "8,900 km",    1620,  5000,  "ok",    "in 1,620 km"),
        Service(NorthstarIcons.Wrench, "Air filter",           "7,200 km",     -80,  8000,  "alert", "overdue 80 km"),
        Service(NorthstarIcons.Gauge,  "Brake pads (front)",   "11,000 km",   2400,  6000,  "ok",    "in 2,400 km"),
        Service(NorthstarIcons.Thermo, "Coolant",              "Apr 02",      4200, 12000,  "ok",    "in 4,200 km"),
    )
    val toneColor = mapOf("ok" to Gold, "warn" to Warn, "alert" to Alert)

    // Chain hero card
    NorthstarCard(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("Chain care")
                Text("Clean & lube", color = TextHi, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily, letterSpacing = (-0.36).sp, modifier = Modifier.padding(top = 5.dp))
            }
            NorthstarChip("Due soon", ChipTone.Warn, dot = true)
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text("380", color = TextHi, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
            Spacer(Modifier.width(6.dp))
            Text("/ 500 km ridden", color = TextLo, fontSize = 13.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 3.dp))
        }

        Spacer(Modifier.height(8.dp))

        // Progress bar
        Box(
            modifier = Modifier
                .fillMaxWidth().height(8.dp)
                .clip(CircleShape).background(Surf3),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(0.76f).fillMaxHeight()
                    .clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(GoldDeep, Warn)))
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NorthstarBtn("Mark done today", onClick = {}, icon = NorthstarIcons.Check, variant = BtnVariant.Primary, size = BtnSize.Sm, modifier = Modifier.weight(1f))
            NorthstarBtn("Remind", onClick = {}, icon = NorthstarIcons.Bell, variant = BtnVariant.Secondary, size = BtnSize.Sm)
        }
    }

    Eyebrow("Service intervals", Modifier.padding(bottom = 8.dp, start = 4.dp))

    NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
        services.forEachIndexed { i, s ->
            if (i > 0) NorthstarDivider(Modifier.padding(horizontal = 4.dp))
            val color = toneColor[s.tone] ?: Gold
            val fill = ((1f - s.leftKm.toFloat().coerceAtLeast(0f) / s.of.toFloat()) * 100f).coerceIn(6f, 100f)

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(11.dp)),
                ) {
                    Icon(s.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text("Last · ${s.last}", color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(s.due, color = color, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier.width(56.dp).height(4.dp).clip(CircleShape).background(Surf3)
                    ) {
                        Box(Modifier.fillMaxWidth(fill / 100f).fillMaxHeight().clip(CircleShape).background(color))
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    NorthstarBtn("Log a service", onClick = {}, icon = NorthstarIcons.Plus, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun FuelTab() {
    data class Fill(val date: String, val litres: String, val cost: String, val kmpl: String, val loc: String)
    val fills = listOf(
        Fill("Jun 06", "12.4", "₹1,290", "31.2", "HP Petrol, Shimla"),
        Fill("May 30", "11.8", "₹1,225", "29.8", "IOC, Narkanda"),
        Fill("May 24", "13.1", "₹1,360", "32.6", "BP, Solan"),
        Fill("May 17", "12.0", "₹1,248", "30.4", "HP Petrol, Shimla"),
    )

    // Efficiency hero
    NorthstarCard(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column {
                Eyebrow("Avg. efficiency · 30 days")
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 7.dp)) {
                    Text("31.0", color = Gold, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily, letterSpacing = (-0.76).sp)
                    Spacer(Modifier.width(6.dp))
                    Text("km / l", color = TextMid, fontSize = 14.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
            NorthstarChip("+4.2%", ChipTone.Gold, icon = NorthstarIcons.Trend)
        }

        NorthstarBarChart(
            data = listOf(
                BarEntry("M17", 30.4f), BarEntry("M24", 32.6f),
                BarEntry("M30", 29.8f), BarEntry("J06", 31.2f),
            ),
            height = 108.dp,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Totals
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 18.dp)) {
        listOf(Pair("₹5,123", "Spent · 30 days"), Pair("49.3 l", "Fuel · 4 fills")).forEach { (v, k) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surf1)
                    .border(1.dp, Line, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(v, color = TextHi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                Eyebrow(k, Modifier.padding(top = 4.dp))
            }
        }
    }

    Eyebrow("Fill-ups", Modifier.padding(bottom = 8.dp, start = 4.dp))

    NorthstarCard(modifier = Modifier.fillMaxWidth(), padding = 6.dp) {
        fills.forEachIndexed { i, f ->
            if (i > 0) NorthstarDivider(Modifier.padding(horizontal = 4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 12.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Surf2).border(1.dp, Line, RoundedCornerShape(11.dp)),
                ) {
                    Icon(NorthstarIcons.Fuel, contentDescription = null, tint = TextMid, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("${f.litres} l · ${f.cost}", color = TextHi, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily)
                    Text("${f.date} · ${f.loc}", color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(f.kmpl, color = Gold, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                    Eyebrow("km/l", Modifier.padding(top = 2.dp))
                }
            }
        }
    }

    Spacer(Modifier.height(14.dp))

    NorthstarBtn("Add fill-up", onClick = {}, icon = NorthstarIcons.Plus, variant = BtnVariant.Ghost, size = BtnSize.Md, modifier = Modifier.fillMaxWidth())
}
