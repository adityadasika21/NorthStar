package com.example.northstar.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.northstar.dash.nav.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

// Free, keyless, redistributable vector basemap (look-first, per the distribution decision).
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val FOLLOW_ZOOM = 15.5
private const val NAV_ZOOM = 17.5
private const val NAV_TILT = 45.0
private const val RIDER_ICON = "rider-chevron"
private const val DEST_ICON = "dest-pin"

/**
 * In-app phone map (MapLibre + OpenFreeMap). Keyless and redistributable — no Google
 * Maps SDK / API key. The physical dash still uses the off-screen power-efficient renderer.
 *
 * Modes: [fitRoute] frames the whole route; [navMode] tilts/zooms/rotates to heading with a
 * rider chevron; default follows the rider north-up.
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
    remember { MapLibre.getInstance(context) }
    // textureMode → render into a TextureView, not a SurfaceView. A SurfaceView is a
    // separate window that doesn't clip/animate with Compose navigation, so the old map
    // lingers on screen during the transition; a TextureView composes normally.
    // onCreate(null) runs HERE, exactly once at construction — NOT via the lifecycle observer.
    // Routing it through the observer let onStart/onResume dispatch before onCreate, and pairing
    // an observer ON_DESTROY with the onDispose teardown destroyed the native MapView TWICE — a
    // use-after-free that SIGSEGVs when navigating quickly between map screens (Dash↔Route↔Home).
    val mapView = remember {
        MapView(context, MapLibreMapOptions().textureMode(true)).apply { onCreate(null) }
    }
    // Set the instant we start tearing down (BEFORE onDestroy). The style loads over the NETWORK, so
    // getMapAsync/setStyle callbacks can fire LATE — after the user has already navigated away and the
    // native MapView was freed. Touching the map/style then derefs freed memory → SIGSEGV in
    // libmaplibre.so (the #1 "crashes while switching pages" crash). Every async callback bails on this.
    val destroyed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var lineMgr by remember { mutableStateOf<LineManager?>(null) }
    var symbolMgr by remember { mutableStateOf<SymbolManager?>(null) }
    var styleReady by remember { mutableStateOf(false) }

    // Bind the MapView to the composition lifecycle. Only start/resume/pause/stop go through the
    // observer; create happened at construction and destroy happens ONCE in onDispose — never via
    // the observer, so the native MapView is freed exactly once.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START   -> mapView.onStart()
                Lifecycle.Event.ON_RESUME  -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE   -> mapView.onPause()
                Lifecycle.Event.ON_STOP    -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            destroyed.set(true)   // block any in-flight async callback from touching the freed map
            lifecycleOwner.lifecycle.removeObserver(obs)
            // Do NOT call lineMgr/symbolMgr.onDestroy() here. The symbolicated tombstone shows THAT is
            // the page-switch crash: AnnotationManager.onDestroy() → DraggableAnnotationController →
            // getLayerId → native Layer::getId, which SIGSEGVs when the map's native peer is already
            // gone during fast navigation (and runCatching can't catch a native crash). MapView
            // .onDestroy() frees the style and every layer/manager on it, so the managers are torn
            // down safely for us — manually destroying them first is both redundant and crash-prone.
            runCatching { mapView.onStop() }
            runCatching { mapView.onDestroy() }
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            if (destroyed.get()) return@getMapAsync   // navigated away before the map was ready
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isAttributionEnabled = true   // OSM/OpenFreeMap attribution (keep — licensing)
                isLogoEnabled = false
            }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                // The style loads over the network — by the time it lands the screen may be gone.
                // Creating Line/SymbolManager on a destroyed MapView is the native crash; bail first.
                if (destroyed.get()) return@setStyle
                style.addImage(RIDER_ICON, chevronBitmap())
                style.addImage(DEST_ICON, destPinBitmap())
                lineMgr = LineManager(mapView, m, style)
                symbolMgr = SymbolManager(mapView, m, style).apply {
                    iconAllowOverlap = true; iconIgnorePlacement = true
                }
                styleReady = true
            }
        }
    }

    // Redraw route + markers whenever the data or mode changes.
    LaunchedEffect(styleReady, routePoints.size, dest, riderLat, riderLng, riderBearing, navMode) {
        if (destroyed.get()) return@LaunchedEffect
        val lm = lineMgr ?: return@LaunchedEffect
        val sm = symbolMgr ?: return@LaunchedEffect
        lm.deleteAll(); sm.deleteAll()
        if (routePoints.size >= 2) {
            lm.create(
                LineOptions().withLatLngs(routePoints.map { LatLng(it.lat, it.lng) })
                    .withLineColor("#4285F4").withLineWidth(5.5f)
            )
        }
        dest?.let { sm.create(SymbolOptions().withLatLng(LatLng(it.first, it.second)).withIconImage(DEST_ICON).withIconSize(1.1f)) }
        if (navMode && riderLat != null && riderLng != null) {
            sm.create(
                SymbolOptions().withLatLng(LatLng(riderLat, riderLng))
                    .withIconImage(RIDER_ICON).withIconRotate(riderBearing).withIconSize(1.0f)
            )
        }
    }

    // Camera control.
    LaunchedEffect(styleReady, riderLat, riderLng, riderBearing, navMode, fitRoute, routePoints.size) {
        if (destroyed.get()) return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        when {
            fitRoute && routePoints.size >= 2 -> {
                val b = LatLngBounds.Builder()
                routePoints.forEach { b.include(LatLng(it.lat, it.lng)) }
                runCatching { m.animateCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 110)) }
            }
            riderLat != null && riderLng != null -> {
                val target = LatLng(riderLat, riderLng)
                val pos = if (navMode)
                    CameraPosition.Builder().target(target).zoom(NAV_ZOOM).tilt(NAV_TILT).bearing(riderBearing.toDouble()).build()
                else
                    CameraPosition.Builder().target(target).zoom(FOLLOW_ZOOM).tilt(0.0).bearing(0.0).build()
                runCatching { m.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 600) }
            }
            dest != null -> runCatching {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(dest.first, dest.second), 13.0))
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Google-style blue chevron-in-a-circle, pointing "up" (rotated to heading by the symbol). */
private fun chevronBitmap(): Bitmap {
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
    c.drawPath(Path().apply {
        moveTo(cx, s * 0.24f); lineTo(s * 0.72f, s * 0.70f); lineTo(cx, s * 0.58f); lineTo(s * 0.28f, s * 0.70f); close()
    }, p)
    return bmp
}

/** Simple red destination pin (white ring + red fill). */
private fun destPinBitmap(): Bitmap {
    val s = 72
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.30f, p)
    p.color = android.graphics.Color.rgb(234, 67, 53); c.drawCircle(s / 2f, s / 2f, s * 0.24f, p)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s * 0.09f, p)
    return bmp
}
