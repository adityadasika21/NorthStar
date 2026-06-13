package com.example.northstar.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.northstar.dash.nav.GeoPoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState

private const val FOLLOW_ZOOM = 15.5f
private const val NAV_ZOOM = 17.5f
private const val NAV_TILT = 45f
private val RouteBlue = Color(0xFF4285F4) // Google Maps directions blue

/**
 * The real Google Maps view shown inside the app's Dash screen.
 *
 * Modes:
 *  - [fitRoute] = route-preview: frames the whole route (overview).
 *  - [navMode]  = Google-Maps navigation: tilts, zooms in, and rotates the map to
 *    the travel heading with a blue chevron marking the rider's position/direction.
 *  - default: follow the rider north-up at street zoom.
 *
 * In-app (phone-screen) map only — the physical dash uses the off-screen renderer.
 */
@Composable
fun NorthstarMap(
    riderLat: Double?,
    riderLng: Double?,
    dest: Pair<Double, Double>?,
    routePoints: List<GeoPoint>,
    hasLocationPermission: Boolean,
    modifier: Modifier = Modifier,
    fitRoute: Boolean = false,
    navMode: Boolean = false,
    riderBearing: Float = 0f,
) {
    val context = LocalContext.current

    val initial = remember(riderLat, riderLng, dest) {
        when {
            riderLat != null && riderLng != null -> LatLng(riderLat, riderLng) to (if (navMode) NAV_ZOOM else FOLLOW_ZOOM)
            dest != null -> LatLng(dest.first, dest.second) to 13f
            else -> lastKnownLatLng(context)?.let { it to FOLLOW_ZOOM }
                ?: (LatLng(20.59, 78.96) to 4f) // India fallback, never 0,0
        }
    }

    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initial.first, initial.second)
    }
    var mapLoaded by remember { mutableStateOf(false) }
    // BitmapDescriptorFactory needs the Maps SDK initialised first — initialise
    // before building the chevron, and tolerate failure (null → skip custom marker).
    val chevron = remember {
        runCatching {
            MapsInitializer.initialize(context.applicationContext)
            chevronIcon()
        }.getOrNull()
    }

    // Route-preview: frame the whole route once laid out.
    LaunchedEffect(mapLoaded, routePoints.size, fitRoute) {
        if (fitRoute && mapLoaded && routePoints.size >= 2) {
            val b = LatLngBounds.builder()
            routePoints.forEach { b.include(LatLng(it.lat, it.lng)) }
            runCatching { camera.animate(CameraUpdateFactory.newLatLngBounds(b.build(), 90)) }
        }
    }

    // Follow the rider. In nav mode, rotate to heading + tilt (Google nav view).
    LaunchedEffect(riderLat, riderLng, riderBearing, navMode) {
        if (!fitRoute && riderLat != null && riderLng != null) {
            val target = LatLng(riderLat, riderLng)
            val pos = if (navMode) {
                CameraPosition.Builder()
                    .target(target).zoom(NAV_ZOOM).tilt(NAV_TILT).bearing(riderBearing).build()
            } else {
                val z = camera.position.zoom.takeIf { it >= 8f } ?: FOLLOW_ZOOM
                CameraPosition.fromLatLngZoom(target, z)
            }
            runCatching { camera.animate(CameraUpdateFactory.newCameraPosition(pos)) }
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        onMapLoaded = { mapLoaded = true },
        properties = MapProperties(
            // In nav mode we draw our own chevron, so hide the default dot.
            isMyLocationEnabled = hasLocationPermission && !navMode,
            mapType = MapType.NORMAL,
        ),
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
            compassEnabled = false,
            mapToolbarEnabled = false,
            tiltGesturesEnabled = false,
            rotationGesturesEnabled = false,
        ),
    ) {
        if (routePoints.size >= 2) {
            val pts = routePoints.map { LatLng(it.lat, it.lng) }
            Polyline(points = pts, color = Color.White, width = 22f)
            Polyline(points = pts, color = RouteBlue, width = 14f)
        }
        dest?.let {
            Marker(
                state = rememberMarkerState(key = "dest", position = LatLng(it.first, it.second)),
                title = "Destination",
            )
        }
        // Rider chevron pointing in the travel direction (nav mode).
        if (navMode && chevron != null && riderLat != null && riderLng != null) {
            Marker(
                state = rememberMarkerState(key = "rider", position = LatLng(riderLat, riderLng)),
                icon = chevron,
                rotation = riderBearing,
                flat = true,
                anchor = Offset(0.5f, 0.5f),
                zIndex = 2f,
            )
        }
    }
}

/** A Google-style blue chevron-in-a-circle, pointing "up" (rotated to heading at draw). */
private fun chevronIcon(): BitmapDescriptor {
    val s = 84
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE
    c.drawCircle(s / 2f, s / 2f, s * 0.34f, p)
    p.color = android.graphics.Color.rgb(66, 133, 244)
    c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.WHITE
    val cx = s / 2f
    val path = Path().apply {
        moveTo(cx, s * 0.24f)
        lineTo(s * 0.72f, s * 0.70f)
        lineTo(cx, s * 0.58f)
        lineTo(s * 0.28f, s * 0.70f)
        close()
    }
    c.drawPath(path, p)
    return BitmapDescriptorFactory.fromBitmap(bmp)
}

@SuppressLint("MissingPermission")
private fun lastKnownLatLng(context: Context): LatLng? {
    return try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        loc?.let { LatLng(it.latitude, it.longitude) }
    } catch (e: Exception) {
        null
    }
}
