package com.example.northstar.dash

import android.util.Log
import com.example.northstar.dash.protocol.DashCommands
import com.example.northstar.dash.protocol.K1GPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DashState { IDLE, CONNECTING, AUTHENTICATING, READY, STREAMING, ERROR }

/**
 * Tripper Dash session, sequenced to match better-dash (tripper_app_like_nav.py):
 *   1. Open sockets (RX :2002 bound first).
 *   2. Send initial burst on :2000 (includes q3c.e request-auth).
 *   3. RX loop ingests 07 00 / 07 03 → sends q3c.d → waits for 07 01 01.
 *   4. Nav entry: route-card ×4 → projectionFrame → z2 (once) → route-card.
 *   5. Start RTP + 4 Hz projection heartbeat + 1 Hz route-card keep-alive.
 * The RX loop runs the WHOLE time, answering auth, 09 06 IDR-decoded acks,
 * and 09 00 button events.
 */
class DashSession(private val scope: CoroutineScope) {
    companion object {
        private const val TAG           = "DashSession"
        private const val AUTH_TIMEOUT  = 15_000L
        private const val BURST_PAUSE   = 20L
        private const val PROJ_HB_MS     = 250L   // 4 Hz
        private const val ROUTE_CARD_MS  = 1_000L // 1 Hz keep-alive
        private const val HOSTNAME       = "Northstar"
    }

    private val _state = MutableStateFlow(DashState.IDLE)
    val state = _state.asStateFlow()

    private var socket: DashSocket? = null
    private var auth: DashAuth? = null
    @Volatile private var authConfirmed = false
    @Volatile private var authRetries = 0

    var onButton: ((Byte) -> Unit)? = null
    var onError:  ((String) -> Unit)? = null

    @Volatile var destinationName: String = "Northstar"

    private var rxJob: Job? = null
    private var projHbJob: Job? = null
    private var routeCardJob: Job? = null
    private var heartbeatJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────

    fun connect(ssid: String) {
        if (_state.value != DashState.IDLE && _state.value != DashState.ERROR) return
        Log.i(TAG, "connect() — ssid='$ssid'")
        scope.launch(Dispatchers.IO) { runSession(ssid) }
    }

    fun startStreaming() {
        if (_state.value != DashState.READY) return
        _state.value = DashState.STREAMING
        // Projection-on route card + faster keep-alive begin now
        launchProjectionHeartbeat()
        launchRouteCardKeepAlive()
    }

    fun sendRtp(packet: ByteArray) { socket?.sendRtp(packet) }

    fun updateRouteCard(name: String) {
        destinationName = name.ifBlank { "Northstar" }
        if (_state.value == DashState.READY || _state.value == DashState.STREAMING) {
            scope.launch(Dispatchers.IO) {
                socket?.send(DashCommands.routeCard(destinationName, projectionOn = true))
            }
        }
    }

    fun disconnect() {
        rxJob?.cancel(); projHbJob?.cancel(); routeCardJob?.cancel(); heartbeatJob?.cancel()
        socket?.let {
            runCatching { it.send(DashCommands.projectionStop()) }
            runCatching { it.send(DashCommands.projectionOff()) }
            it.close()
        }
        socket = null
        _state.value = DashState.IDLE
        Log.i(TAG, "Disconnected")
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private suspend fun runSession(ssid: String) {
        try {
            _state.value = DashState.CONNECTING
            val sock = try {
                DashSocket().also { socket = it }
            } catch (e: java.net.BindException) {
                fail("Port ${DashSocket.RX_PORT}/${DashSocket.CTRL_PORT} in use (${e.message})")
                return
            }

            auth = DashAuth(ssid)
            authConfirmed = false
            authRetries = 0

            // RX loop MUST be running before the burst (early pubkey + no ICMP).
            launchReceiveLoop(sock)
            // 1 Hz status heartbeat throughout the session.
            launchStatusHeartbeat(sock)

            _state.value = DashState.AUTHENTICATING
            Log.i(TAG, "Sending initial burst…")
            for (pkt in DashCommands.initialBurst(HOSTNAME)) {
                sock.send(pkt)
                delay(BURST_PAUSE)
            }

            Log.i(TAG, "Waiting up to ${AUTH_TIMEOUT}ms for auth (07 01 01)…")
            val deadline = System.currentTimeMillis() + AUTH_TIMEOUT
            while (!authConfirmed && System.currentTimeMillis() < deadline) delay(100)

            if (!authConfirmed) {
                fail("Auth timed out — no 07 01 01 from dash. Check SSID matches '$ssid'.")
                return
            }
            Log.i(TAG, "Authenticated ✓")

            enterNavMode(sock)
            _state.value = DashState.READY
            Log.i(TAG, "READY ✓")

        } catch (e: Exception) {
            Log.e(TAG, "Session error", e)
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Nav entry in the exact phone order (nav_open_ok.pcap):
     *   route-card ×4 (establishes destination) → projectionFrame
     *   → z2 once → route-card confirmation.
     */
    private suspend fun enterNavMode(sock: DashSocket) {
        sock.send(DashCommands.navContext()); delay(40)
        sock.send(DashCommands.emptyLists()); delay(40)

        repeat(4) {
            sock.send(DashCommands.routeCard(destinationName, projectionOn = false))
            delay(if (it < 1) 100 else 500)
        }
        sock.send(DashCommands.projectionFrame()); delay(60)
        sock.send(DashCommands.navPlaceholder()); delay(10)
        sock.send(DashCommands.navStart()); delay(40)                 // z2, ONCE
        sock.send(DashCommands.routeCard(destinationName, projectionOn = true))
        Log.i(TAG, "Nav mode kick sent")
    }

    private fun launchReceiveLoop(sock: DashSocket) {
        rxJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pkt = sock.receive() ?: continue
                dispatchIncoming(pkt, sock)
            }
        }
    }

    private fun dispatchIncoming(pkt: ByteArray, sock: DashSocket) {
        val tlvs = K1GPacket.parseIncoming(pkt)
        for (tlv in tlvs) {
            // ── Auth (07 xx) ──
            if (tlv.type == 0x07) {
                when (val ev = auth?.ingest(tlv)) {
                    is AuthEvent.SendKey -> {
                        Log.i(TAG, "Got RSA pubkey — sending q3c.d")
                        sock.send(ev.packet)
                    }
                    AuthEvent.Confirmed -> { authConfirmed = true }
                    AuthEvent.Rejected -> {
                        authRetries++
                        Log.w(TAG, "Auth rejected — retry #$authRetries")
                        auth?.reset()
                        if (authRetries <= 5) sock.send(DashCommands.authRequest())
                    }
                    else -> {}
                }
                continue
            }
            // ── 09 06 55: per-IDR frame-decoded notify → mandatory q3c.L2 ──
            if (tlv.type == 0x09 && tlv.sub == 0x06 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                sock.send(DashCommands.frameDecodedIdr())
                continue
            }
            // ── 09 04 55: P-frame decoded → q3c.K2 ──
            if (tlv.type == 0x09 && tlv.sub == 0x04 &&
                tlv.value.firstOrNull()?.toInt() == 0x55
            ) {
                sock.send(DashCommands.frameDecodedP())
                continue
            }
            // ── 09 00: button / joystick event → echo ack + notify UI ──
            if (tlv.type == 0x09 && tlv.sub == 0x00 && tlv.value.isNotEmpty()) {
                val btn = tlv.value.last()  // 0900 0001 <code>
                sock.send(DashCommands.buttonAck(btn))
                scope.launch(Dispatchers.Main) { onButton?.invoke(btn) }
            }
        }
    }

    private fun launchStatusHeartbeat(sock: DashSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { sock.send(DashCommands.heartbeat()) }
                delay(1_000)
            }
        }
    }

    private fun launchProjectionHeartbeat() {
        projHbJob?.cancel()
        projHbJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                socket?.send(DashCommands.projectionFrame())
                delay(PROJ_HB_MS)
            }
        }
    }

    private fun launchRouteCardKeepAlive() {
        routeCardJob?.cancel()
        routeCardJob = scope.launch(Dispatchers.IO) {
            while (isActive && _state.value == DashState.STREAMING) {
                socket?.send(DashCommands.routeCard(destinationName, projectionOn = true))
                delay(ROUTE_CARD_MS)
            }
        }
    }

    private fun fail(msg: String) {
        Log.e(TAG, "ERROR — $msg")
        rxJob?.cancel(); heartbeatJob?.cancel()
        socket?.close(); socket = null
        _state.value = DashState.ERROR
        onError?.invoke(msg)
    }
}
