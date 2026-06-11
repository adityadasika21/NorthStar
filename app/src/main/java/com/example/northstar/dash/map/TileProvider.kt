package com.example.northstar.dash.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * OSM raster tile provider with memory + disk cache.
 *
 * While riding, the process is bound to the Tripper's WiFi (no internet),
 * so tiles MUST come from the disk cache — call [prefetch] while internet
 * is still reachable (when a destination is shared). On a cache miss with
 * internet available, tiles are fetched through whichever network actually
 * has connectivity (cellular when bound to the dash WiFi).
 */
class TileProvider(context: Context, private val scope: CoroutineScope) {
    companion object {
        private const val TAG = "TileProvider"
        private const val URL_TEMPLATE = "https://tile.openstreetmap.org/%d/%d/%d.png"
        // OSM tile usage policy requires an identifying UA
        private const val USER_AGENT = "Northstar/1.0 (personal motorcycle nav; single user)"
        private const val MAX_PREFETCH_TILES = 400
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val diskDir = File(context.cacheDir, "tiles").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(96)
    private val inflight = ConcurrentHashMap.newKeySet<String>()

    /** Non-blocking: returns the tile if cached in memory, else kicks off async load. */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val max = 1 shl z
        if (y < 0 || y >= max) return null
        val xw = ((x % max) + max) % max // wrap longitude
        val key = "$z/$xw/$y"

        memory.get(key)?.let { return it }

        if (inflight.add(key)) {
            scope.launch(Dispatchers.IO) {
                try {
                    val bmp = loadDisk(key) ?: fetch(z, xw, y, key)
                    if (bmp != null) memory.put(key, bmp)
                } finally {
                    inflight.remove(key)
                }
            }
        }
        return null
    }

    /**
     * Download tiles around a point at riding zoom levels into the disk cache.
     * Call while internet is reachable (destination share time).
     */
    fun prefetch(lat: Double, lng: Double, fromLat: Double? = null, fromLng: Double? = null) {
        scope.launch(Dispatchers.IO) {
            var count = 0
            Log.i(TAG, "Prefetch around %.4f,%.4f".format(lat, lng))
            for (z in 11..16) {
                val radius = if (z >= 15) 2 else 1
                count += prefetchAround(lat, lng, z, radius)
                if (fromLat != null && fromLng != null) {
                    count += prefetchAround(fromLat, fromLng, z, radius)
                    // Straight-line corridor between start and destination
                    if (z in 12..13) {
                        for (i in 1..8) {
                            val f = i / 9.0
                            count += prefetchAround(
                                fromLat + (lat - fromLat) * f,
                                fromLng + (lng - fromLng) * f,
                                z, 1,
                            )
                        }
                    }
                }
                if (count > MAX_PREFETCH_TILES) break
            }
            Log.i(TAG, "Prefetch done — ~$count tiles ensured")
        }
    }

    private fun prefetchAround(lat: Double, lng: Double, z: Int, radius: Int): Int {
        val cx = Mercator.lngToTileX(lng, z).toInt()
        val cy = Mercator.latToTileY(lat, z).toInt()
        var n = 0
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val x = cx + dx
            val y = cy + dy
            if (y < 0 || y >= (1 shl z)) continue
            val key = "$z/$x/$y"
            if (!diskFile(key).exists()) {
                fetch(z, x, y, key)
                n++
            }
        }
        return n
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private fun diskFile(key: String) = File(diskDir, key.replace('/', '_') + ".png")

    private fun loadDisk(key: String): Bitmap? {
        val f = diskFile(key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    private fun fetch(z: Int, x: Int, y: Int, key: String): Bitmap? {
        val net = internetNetwork()
        return try {
            val url = URL(URL_TEMPLATE.format(z, x, y))
            val conn = (net?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            diskFile(key).writeBytes(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "Tile $key fetch failed: ${e.message}")
            null
        }
    }

    /** Find a network with real internet — needed because the process is bound to the dash WiFi. */
    @Suppress("DEPRECATION")
    private fun internetNetwork(): Network? =
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
        }
}
