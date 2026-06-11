package com.example.northstar.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*

@Composable
fun RidesScreen() {
    data class Ride(val name: String, val date: String, val dist: String, val dur: String, val avg: String, val routePts: List<Offset>)

    val rides = listOf(
        Ride("Shimla → Narkanda",   "Jun 06", "64",  "2h 10m", "38", listOf(Offset(0.19f, 0.83f), Offset(0.36f, 0.57f), Offset(0.47f, 0.40f), Offset(0.55f, 0.24f), Offset(0.61f, 0.14f))),
        Ride("Solan loop",          "May 30", "112", "3h 40m", "46", listOf(Offset(0.22f, 0.74f), Offset(0.42f, 0.71f), Offset(0.47f, 0.52f), Offset(0.39f, 0.38f), Offset(0.53f, 0.26f))),
        Ride("Kufri morning run",   "May 24", "38",  "1h 25m", "34", listOf(Offset(0.17f, 0.71f), Offset(0.33f, 0.64f), Offset(0.28f, 0.48f), Offset(0.42f, 0.43f), Offset(0.58f, 0.17f))),
        Ride("Chail forest trail",  "May 18", "88",  "3h 05m", "41", listOf(Offset(0.22f, 0.79f), Offset(0.35f, 0.60f), Offset(0.30f, 0.48f), Offset(0.50f, 0.45f), Offset(0.55f, 0.26f), Offset(0.58f, 0.19f))),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
            .padding(bottom = 24.dp),
    ) {
        ScreenHeader(
            eyebrow = "Telemetry",
            title = "Ride history",
            trailing = { NorthstarIconBtn(NorthstarIcons.Chart, onClick = {}) },
        )

        // Month summary card
        NorthstarCard(glow = true, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp)) {
            Eyebrow("This month")
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(Triple("302", "km", "Distance"), Triple("10:20", "h", "Saddle time"), Triple("41", "avg", "km/h")).forEachIndexed { i, (v, u, k) ->
                    Column(
                        horizontalAlignment = if (i == 0) Alignment.Start else Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(v, color = if (i == 0) Gold else TextHi, fontSize = if (i == 0) 30.sp else 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                            Spacer(Modifier.width(3.dp))
                            Text(u, color = TextLo, fontSize = 11.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        Eyebrow(k, Modifier.padding(top = 4.dp))
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rides.forEach { ride ->
                NorthstarCard(
                    modifier = Modifier.fillMaxWidth(),
                    padding = 12.dp,
                    onClick = {},
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Mini map snapshot
                        Box(
                            modifier = Modifier
                                .size(60.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.radialGradient(listOf(Color(0xFF14201F), Color(0xFF0B1011))))
                                .border(1.dp, Line2, RoundedCornerShape(14.dp)),
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                val w = size.width; val h = size.height

                                // Grid roads
                                drawLine(Color(0xFF22312F), Offset(w * 0.11f, h * 0.60f), Offset(w * 0.89f, h * 0.67f), 3f)
                                drawLine(Color(0xFF22312F), Offset(w * 0.42f, h * 0.05f), Offset(w * 0.56f, h * 0.95f), 3f)

                                // Route
                                if (ride.routePts.size >= 2) {
                                    val path = Path()
                                    path.moveTo(ride.routePts[0].x * w, ride.routePts[0].y * h)
                                    ride.routePts.drop(1).forEach { pt -> path.lineTo(pt.x * w, pt.y * h) }
                                    drawPath(path, Gold, style = Stroke(3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }

                                // Start dot
                                ride.routePts.firstOrNull()?.let {
                                    drawCircle(Gold, 3.5f, Offset(it.x * w, it.y * h))
                                }
                            }
                        }

                        Spacer(Modifier.width(14.dp))

                        Column(Modifier.weight(1f)) {
                            Text(ride.name, color = TextHi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ride.date, color = TextLo, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                                Text("${ride.dist} km", color = TextMid, fontSize = 12.5.sp, fontFamily = GeistMonoFamily)
                                Text(ride.dur, color = TextMid, fontSize = 12.5.sp, fontFamily = GeistMonoFamily)
                                Text("${ride.avg} km/h", color = TextMid, fontSize = 12.5.sp, fontFamily = GeistMonoFamily)
                            }
                        }

                        Icon(NorthstarIcons.ChevronRight, contentDescription = null, tint = TextLo, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
