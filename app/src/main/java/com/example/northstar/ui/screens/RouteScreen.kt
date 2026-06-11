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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.northstar.ui.NorthstarIcons
import com.example.northstar.ui.components.*
import com.example.northstar.ui.theme.*
import com.example.northstar.viewmodel.RouteViewModel

@Composable
fun RouteScreen(
    onBack: () -> Unit,
    onSentToDash: (String) -> Unit,
    routeViewModel: RouteViewModel = viewModel(),
) {
    val routeState by routeViewModel.state.collectAsState()
    val dest       = routeState.destination
    val destName   = dest?.name?.ifBlank { "Shared location" } ?: "Shared location"
    val destSub    = when {
        dest?.lat != null && dest.lng != null ->
            "%.5f, %.5f".format(dest.lat, dest.lng)
        dest?.url != null -> "Maps link"
        else              -> ""
    }

    var voice by remember { mutableStateOf("Chime only") }
    var sent by remember { mutableStateOf(false) }

    LaunchedEffect(sent) {
        if (sent) {
            kotlinx.coroutines.delay(650)
            onSentToDash(destName)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Map preview (top 46%)
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.46f)
                .background(MapBase),
        ) {
            RouteMapPreview()

            // Top bar overlay
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color(0xD9080C0C), Color.Transparent))
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                NorthstarIconBtn(
                    NorthstarIcons.ChevronLeft,
                    onClick = onBack,
                    size = 40.dp,
                    modifier = Modifier.background(Color(0xB30D0F11), IconBtnShape),
                )
                Spacer(Modifier.width(12.dp))
                NorthstarChip(
                    if (routeState.isResolving) "Resolving link…" else "Shared from Google Maps",
                    tone = if (routeState.isResolving) ChipTone.Gold else ChipTone.Neutral,
                    icon = NorthstarIcons.Share,
                    modifier = Modifier.background(Color(0xB30D0F11), CircleShape),
                )
            }
        }

        // Detail sheet (overlapping bottom portion)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = (-22).dp)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Bg1)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp)
                .padding(bottom = 20.dp),
        ) {
            // Drag handle
            Box(
                Modifier
                    .width(40.dp).height(4.dp)
                    .clip(CircleShape).background(Line3)
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 16.dp)
            )

            Spacer(Modifier.height(12.dp))

            Eyebrow("Destination", Modifier.padding(bottom = 6.dp))

            Row(verticalAlignment = Alignment.Top) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GoldTint),
                ) {
                    Icon(NorthstarIcons.LocationPin, contentDescription = null, tint = Gold, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    if (routeState.isResolving) {
                        Text("Resolving…", color = TextLo, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily, letterSpacing = (-0.38).sp)
                    } else {
                        Text(destName, color = TextHi, fontSize = 19.sp, fontWeight = FontWeight.Bold, fontFamily = GeistFamily, letterSpacing = (-0.38).sp)
                    }
                    if (destSub.isNotBlank()) {
                        Text(destSub, color = TextMid, fontSize = 13.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Route stats
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple("218", "km", "Distance"), Triple("4:50", "hrs", "Duration"), Triple("13:32", "ETA", "Arrive")).forEach { (v, u, k) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Surf1)
                            .border(1.dp, Line, RoundedCornerShape(14.dp))
                            .padding(13.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(v, color = TextHi, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = GeistMonoFamily)
                            Spacer(Modifier.width(4.dp))
                            Text(u, color = TextLo, fontSize = 11.sp, fontFamily = GeistMonoFamily, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        Eyebrow(k, Modifier.padding(top = 4.dp))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // Route note
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Surf1)
                    .border(1.dp, Line, RoundedCornerShape(14.dp))
                    .padding(14.dp),
            ) {
                Icon(NorthstarIcons.Road, contentDescription = null, tint = Gold, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "Twisty mountain route · ",
                    color = TextMid, fontSize = 13.sp,
                )
                Text("3 fuel stops", color = TextHi, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(" en route", color = TextMid, fontSize = 13.sp)
            }

            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(bottom = 9.dp),
            ) {
                Eyebrow("Voice guidance")
                Icon(
                    if (voice == "Off") NorthstarIcons.SpeakerOff else NorthstarIcons.Speaker,
                    contentDescription = null,
                    tint = if (voice == "Off") TextLo else Gold,
                    modifier = Modifier.size(18.dp),
                )
            }

            NorthstarSegmented(
                options = listOf("Off", "Chime only", "Full TTS"),
                selected = voice,
                onSelect = { voice = it },
                modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
            )

            NorthstarBtn(
                label = when {
                    sent                   -> "Sent — opening dash…"
                    routeState.isResolving -> "Resolving destination…"
                    else                   -> "Send to Dash"
                },
                onClick = { sent = true },
                icon = if (sent) NorthstarIcons.Check else NorthstarIcons.Share,
                variant = if (sent) BtnVariant.Secondary else BtnVariant.Primary,
                size = BtnSize.Lg,
                enabled = !sent && !routeState.isResolving,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RouteMapPreview() {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // Background gradient
        drawRect(
            brush = Brush.verticalGradient(listOf(MapBase, Color(0xFF0B1012))),
        )

        // Route path (gold)
        val routePath = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.22f, h * 0.81f)
            cubicTo(w * 0.31f, h * 0.70f, w * 0.23f, h * 0.59f, w * 0.38f, h * 0.51f)
            cubicTo(w * 0.54f, h * 0.43f, w * 0.46f, h * 0.31f, w * 0.64f, h * 0.23f)
            cubicTo(w * 0.74f, h * 0.18f, w * 0.77f, h * 0.12f, w * 0.76f, h * 0.08f)
        }

        drawPath(routePath, Gold, style = androidx.compose.ui.graphics.drawscope.Stroke(
            width = 6f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        ))

        // Dashed white overlay
        drawPath(routePath,
            color = Color(0x8CFFF4D8),
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 1.6f,
                cap = StrokeCap.Round,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2f, 8f)),
            )
        )

        // Start dot
        drawCircle(Color(0xFF0E1416), radius = 9f, center = Offset(w * 0.22f, h * 0.81f))
        drawCircle(Gold, radius = 9f, center = Offset(w * 0.22f, h * 0.81f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        drawCircle(Gold, radius = 3.5f, center = Offset(w * 0.22f, h * 0.81f))

        // End pin (gold circle)
        drawCircle(Gold, radius = 13f, center = Offset(w * 0.76f, h * 0.08f))
        drawCircle(Color(0xFF1A1402), radius = 5f, center = Offset(w * 0.76f, h * 0.08f))
    }
}
