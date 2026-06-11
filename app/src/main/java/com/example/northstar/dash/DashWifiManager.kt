package com.example.northstar.dash

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class WifiConnStatus { IDLE, REQUESTING, CONNECTED, ERROR }

data class WifiState(
    val status: WifiConnStatus = WifiConnStatus.IDLE,
    val ssid: String = "",
    val error: String? = null,
)

/**
 * Programmatically connects to the Tripper Dash WiFi hotspot using
 * WifiNetworkSpecifier + ConnectivityManager.requestNetwork().
 *
 * bindProcessToNetwork() routes all UDP/TCP from this process through
 * the Tripper network so packets reach 192.168.1.1 even if the phone
 * has another network (cellular) available.
 *
 * Auto-reconnects on link loss until disconnect() is called.
 */
class DashWifiManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG              = "DashWifiManager"
        private const val CONNECT_TIMEOUT  = 30_000  // ms — Android shows system dialog within this
        private const val RECONNECT_DELAY  = 4_000L
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(WifiState())
    val state = _state.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var reconnectJob: Job? = null
    private var wantConnected = false
    private var pendingSsid = ""
    private var pendingPassword = ""

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Request the given WiFi network. Shows a one-time system confirmation
     * dialog ("Northstar wants to connect to <ssid>"). Pass an empty password
     * for open (no-password) networks.
     */
    fun connect(ssid: String, password: String = "") {
        wantConnected    = true
        pendingSsid      = ssid
        pendingPassword  = password
        requestNetwork()
    }

    fun disconnect() {
        Log.i(TAG, "Disconnect requested")
        wantConnected = false
        reconnectJob?.cancel()
        release()
        _state.value = WifiState()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun requestNetwork() {
        release()
        Log.i(TAG, "Requesting WiFi: '$pendingSsid' (password=${if (pendingPassword.isBlank()) "none" else "set"})")
        _state.value = WifiState(status = WifiConnStatus.REQUESTING, ssid = pendingSsid)

        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(pendingSsid)
        if (pendingPassword.isNotBlank()) specBuilder.setWpa2Passphrase(pendingPassword)

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "WiFi connected ✓  network=$network")
                cm.bindProcessToNetwork(network)
                reconnectJob?.cancel()
                _state.value = WifiState(status = WifiConnStatus.CONNECTED, ssid = pendingSsid)
            }

            override fun onUnavailable() {
                Log.w(TAG, "WiFi unavailable — SSID not found or user declined")
                cm.bindProcessToNetwork(null)
                _state.value = WifiState(
                    status = WifiConnStatus.ERROR,
                    ssid   = pendingSsid,
                    error  = "Could not connect to '$pendingSsid' — network not found or wrong password",
                )
                // Don't auto-retry onUnavailable; user must try again
                wantConnected = false
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "WiFi link lost — reconnecting in ${RECONNECT_DELAY}ms")
                cm.bindProcessToNetwork(null)
                _state.value = WifiState(
                    status = WifiConnStatus.REQUESTING,
                    ssid   = pendingSsid,
                    error  = "Link lost — reconnecting…",
                )
                if (wantConnected) scheduleReconnect()
            }
        }

        networkCallback = cb
        try {
            cm.requestNetwork(request, cb, CONNECT_TIMEOUT)
        } catch (e: Exception) {
            Log.e(TAG, "requestNetwork threw: ${e.message}", e)
            _state.value = WifiState(
                status = WifiConnStatus.ERROR,
                ssid   = pendingSsid,
                error  = "${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY)
            if (wantConnected) requestNetwork()
        }
    }

    private fun release() {
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        cm.bindProcessToNetwork(null)
    }
}
