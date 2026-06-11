package com.example.northstar.util

import com.example.northstar.data.SharedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

object LocationParser {
    private val urlRegex  = Regex("https?://[^\\s)]+")
    private val coordAt   = Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val coordQ    = Regex("[?&]q=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val coordLl   = Regex("[?&]ll=(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val placePath = Regex("/place/([^/@?]+)")
    private val placeQ    = Regex("[?&]q=([^&0-9\\-@][^&]*)")

    fun parse(text: String): SharedLocation {
        val trimmed = text.trim()
        val url = urlRegex.find(trimmed)?.value

        val isShort = url != null &&
            (url.contains("maps.app.goo.gl") || url.contains("goo.gl/maps"))

        val textBefore = url?.let { trimmed.substringBefore(it).trim() }
        val textName   = textBefore?.lines()?.lastOrNull { it.isNotBlank() }?.trim()

        val name = when {
            !textName.isNullOrBlank() -> textName
            url != null && !isShort   -> extractPlaceName(url) ?: "Shared location"
            else                      -> "Loading…"
        }

        val coords = if (url != null && !isShort) extractCoords(url) else null

        return SharedLocation(
            name           = name,
            lat            = coords?.first,
            lng            = coords?.second,
            url            = url,
            needsExpansion = isShort,
        )
    }

    fun extractCoords(url: String): Pair<Double, Double>? {
        for (regex in listOf(coordAt, coordQ, coordLl)) {
            val m   = regex.find(url) ?: continue
            val lat = m.groupValues[1].toDoubleOrNull() ?: continue
            val lng = m.groupValues[2].toDoubleOrNull() ?: continue
            return lat to lng
        }
        return null
    }

    fun extractPlaceName(url: String): String? {
        placePath.find(url)?.let { m ->
            return URLDecoder.decode(m.groupValues[1].replace("+", " "), "UTF-8")
                .replace(Regex("[_-]"), " ").trim().ifBlank { null }
        }
        placeQ.find(url)?.let { m ->
            return URLDecoder.decode(m.groupValues[1].replace("+", " "), "UTF-8")
                .trim().ifBlank { null }
        }
        return null
    }

    suspend fun expandShortUrl(url: String): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 5_000
            conn.readTimeout    = 5_000
            conn.requestMethod  = "HEAD"
            conn.connect()
            val expanded = conn.url.toString()
            conn.disconnect()
            expanded
        } catch (_: Exception) {
            url
        }
    }
}
