package com.example.northstar.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.northstar.dash.DashKeepAliveService
import com.example.northstar.dash.DashSession
import com.example.northstar.dash.DashState
import com.example.northstar.dash.DashWifiManager
import com.example.northstar.dash.WifiConnStatus
import com.example.northstar.dash.map.LocationTracker
import com.example.northstar.dash.map.MapRenderer
import com.example.northstar.dash.map.Mercator
import com.example.northstar.dash.map.TileProvider
import com.example.northstar.dash.protocol.DashCommands
import com.example.northstar.dash.video.DashEncoder
import com.example.northstar.dash.video.NalProcessor
import com.example.northstar.dash.video.RtpPacketizer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One user-facing connection stage derived from WiFi + dash session state. */
enum class ConnStage { OFFLINE, WIFI, AUTH, STREAMING, ERROR }

data class DashUiState(
    val stage: ConnStage = ConnStage.OFFLINE,
    val frameCount: Int = 0,
    val lastButton: String? = null,
    val ssid: String = "RE_P0RP_260525",
    val wifiPassword: String = "12345678",
    val destinationName: String? = null,
    val errorMessage: String? = null,
    val mapZoom: Int = 14,
    val remainingKm: Double? = null,
    val hasGps: Boolean = false,
)

class DashViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(DashUiState())
    val ui = _ui.asStateFlow()

    private val session     = DashSession(viewModelScope)
    private val wifiManager = DashWifiManager(app, viewModelScope)
    private val tiles       = TileProvider(app, viewModelScope)
    private val location    = LocationTracker(app)
    private val mapRenderer = MapRenderer(tiles)

    private var encoder: DashEncoder? = null
    private var streamJob: Job? = null

    /** True between connect() and disconnect() — drives auto-reconnect. */
    private var userWantsConnection = false

    // Map view state (read by the frame loop at 4 fps)
    @Volatile private var destLat: Double? = null
    @Volatile private var destLng: Double? = null
    @Volatile private var panX = 0f
    @Volatile private var panY = 0f
    @Volatile private var zoom = 14

    init {
        // WiFi state machine: when WiFi lands, start the dash auth automatically
        viewModelScope.launch {
            wifiManager.state.collect { ws ->
                when (ws.status) {
                    WifiConnStatus.CONNECTED -> {
                        refreshStage()
                        if (userWantsConnection &&
                            session.state.value in listOf(DashState.IDLE, DashState.ERROR)
                        ) {
                            delay(1_200) // let DHCP settle before hitting 192.168.1.1
                            session.connect(_ui.value.ssid)
                        }
                    }
                    WifiConnStatus.ERROR -> {
                        _ui.value = _ui.value.copy(errorMessage = ws.error)
                        refreshStage()
                    }
                    else -> refreshStage()
                }
            }
        }

        viewModelScope.launch {
            session.state.collect { state ->
                refreshStage()
                if (state == DashState.READY) startStream()
            }
        }

        viewModelScope.launch {
            location.location.collect { loc ->
                val dLat = destLat; val dLng = destLng
                _ui.value = _ui.value.copy(
                    hasGps = loc != null,
                    remainingKm = if (loc != null && dLat != null && dLng != null)
                        Mercator.haversineKm(loc.latitude, loc.longitude, dLat, dLng)
                    else null,
                )
            }
        }

        session.onError = { msg -> _ui.value = _ui.value.copy(errorMessage = msg); refreshStage() }
        // Physical joystick → map pan. Exact code↔direction mapping is
        // unverified against fw 11.63; refine once observed on the bike.
        session.onButton = { btn ->
            val label = when (btn) {
                DashCommands.BTN_06 -> { panX += 40f; "→ ($btn)" }
                DashCommands.BTN_07 -> { panX -= 40f; "← ($btn)" }
                DashCommands.BTN_05 -> { panY += 40f; "↓ ($btn)" }
                DashCommands.BTN_09 -> { panY -= 40f; "↑ ($btn)" }
                DashCommands.BTN_0A,
                DashCommands.BTN_22 -> { panX = 0f; panY = 0f; "● recenter ($btn)" }
                else -> "0x${(btn.toInt() and 0xFF).toString(16)}"
            }
            _ui.value = _ui.value.copy(lastButton = label)
        }
    }

    private fun refreshStage() {
        val wifi = wifiManager.state.value.status
        val dash = session.state.value
        val stage = when {
            dash == DashState.STREAMING                                    -> ConnStage.STREAMING
            dash == DashState.ERROR || wifi == WifiConnStatus.ERROR        -> ConnStage.ERROR
            dash == DashState.AUTHENTICATING || dash == DashState.CONNECTING ||
                dash == DashState.READY                                    -> ConnStage.AUTH
            wifi == WifiConnStatus.REQUESTING                              -> ConnStage.WIFI
            wifi == WifiConnStatus.CONNECTED                               -> ConnStage.WIFI
            else                                                           -> ConnStage.OFFLINE
        }
        _ui.value = _ui.value.copy(stage = stage)
    }

    // ── Single-button public API ──────────────────────────────────────────

    /** One tap: WiFi join → dash auth → nav mode → stream. */
    fun connect() {
        userWantsConnection = true
        _ui.value = _ui.value.copy(errorMessage = null)
        // Foreground service + wake/wifi locks so the ride survives screen-off.
        DashKeepAliveService.start(getApplication())
        location.start()
        if (wifiManager.state.value.status == WifiConnStatus.CONNECTED) {
            session.connect(_ui.value.ssid)
        } else {
            wifiManager.connect(_ui.value.ssid, _ui.value.wifiPassword)
        }
    }

    fun disconnect() {
        userWantsConnection = false
        teardown()
        session.disconnect()
        wifiManager.disconnect()
        location.stop()
        DashKeepAliveService.stop(getApplication())
        refreshStage()
    }

    fun setSsid(s: String) { _ui.value = _ui.value.copy(ssid = s) }

    /** Download map tiles while internet is still reachable (destination share time). */
    fun prefetchTiles(lat: Double, lng: Double) {
        val loc = location.location.value
        tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
    }

    fun setDestination(name: String, lat: Double?, lng: Double?) {
        _ui.value = _ui.value.copy(destinationName = name)
        destLat = lat
        destLng = lng
        session.updateRouteCard(name)
        if (lat != null && lng != null) {
            val loc = location.location.value
            tiles.prefetch(lat, lng, loc?.latitude, loc?.longitude)
        }
    }

    // ── Map controls (mirrored by phone UI) ──────────────────────────────

    fun zoomIn()  { zoom = (zoom + 1).coerceAtMost(17); _ui.value = _ui.value.copy(mapZoom = zoom) }
    fun zoomOut() { zoom = (zoom - 1).coerceAtLeast(8);  _ui.value = _ui.value.copy(mapZoom = zoom) }
    fun panBy(dx: Float, dy: Float) { panX += dx; panY += dy }
    fun recenter() { panX = 0f; panY = 0f }

    // ── Video pipeline ────────────────────────────────────────────────────

    private fun startStream() {
        val packetizer = RtpPacketizer { rtpPkt -> session.sendRtp(rtpPkt) }
        val nalProc    = NalProcessor { nal, _ ->
            packetizer.packetize(nal, endOfAU = true, wallClockMs = System.currentTimeMillis())
        }
        val enc = DashEncoder { annexB, _ ->
            nalProc.process(annexB)
            _ui.value = _ui.value.copy(frameCount = _ui.value.frameCount + 1)
        }.also { it.prepare(); encoder = it }

        session.startStreaming()

        // Prefetch tiles around wherever we are right now (via cellular, since the
        // process is bound to the internet-less dash WiFi) so the map isn't blank.
        location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }

        streamJob = viewModelScope.launch(Dispatchers.Default) {
            val intervalMs = 1000L / DashEncoder.FPS
            var lastPrefetch = 0L
            while (isActive && session.state.value == DashState.STREAMING) {
                enc.renderFrame { canvas -> mapRenderer.draw(canvas, buildFrame()) }
                enc.drain()
                // Keep the cache warm ahead of the rider every ~20 s.
                val now = System.currentTimeMillis()
                if (now - lastPrefetch > 20_000) {
                    lastPrefetch = now
                    location.location.value?.let { tiles.prefetch(it.latitude, it.longitude) }
                }
                delay(intervalMs)
            }
        }
    }

    private fun buildFrame(): MapRenderer.Frame {
        val loc  = location.location.value
        val dLat = destLat; val dLng = destLng
        // Map centers on the rider; falls back to destination, then a neutral standby
        val centerLat = loc?.latitude  ?: dLat ?: 0.0
        val centerLng = loc?.longitude ?: dLng ?: 0.0
        return MapRenderer.Frame(
            centerLat = centerLat,
            centerLng = centerLng,
            zoom      = zoom,
            panX      = panX,
            panY      = panY,
            riderLat  = loc?.latitude,
            riderLng  = loc?.longitude,
            destLat   = dLat,
            destLng   = dLng,
            destName  = _ui.value.destinationName,
        )
    }

    private fun teardown() {
        streamJob?.cancel(); streamJob = null
        encoder?.release(); encoder = null
    }

    override fun onCleared() {
        super.onCleared()
        teardown()
        session.disconnect()
        wifiManager.disconnect()
        location.stop()
    }
}
