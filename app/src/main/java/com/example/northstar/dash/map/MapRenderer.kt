package com.example.northstar.dash.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF

/**
 * Draws the navigation frame for the Tripper Dash (526 × 300).
 *
 * Layers: OSM tiles (dark-filtered) → crow-flies line to destination →
 * destination pin → rider dot → top banner with name + distance.
 */
class MapRenderer(private val tiles: TileProvider) {

    data class Frame(
        val centerLat: Double,
        val centerLng: Double,
        val zoom: Int,
        val panX: Float = 0f,           // joystick pan offset in px
        val panY: Float = 0f,
        val riderLat: Double? = null,
        val riderLng: Double? = null,
        val destLat: Double? = null,
        val destLng: Double? = null,
        val destName: String? = null,
    )

    private val bgColor = Color.rgb(17, 19, 21)        // Bg1 #111315
    private val gold    = Color.rgb(233, 185, 73)      // #E9B949

    // Invert luminance + desaturate → dark map from standard OSM tiles
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val desat = ColorMatrix().apply { setSaturation(0.25f) }
        // Dim so the dash isn't blinding at night
        val dim = ColorMatrix(floatArrayOf(
            0.75f, 0f, 0f, 0f, 0f,
            0f, 0.75f, 0f, 0f, 0f,
            0f, 0f, 0.78f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        invert.postConcat(desat)
        invert.postConcat(dim)
        colorFilter = ColorMatrixColorFilter(invert)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    private val dotPaint    = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        isFakeBoldText = true
    }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gold
        textSize = 19f
        isFakeBoldText = true
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 13, 15, 17)
    }

    fun draw(canvas: Canvas, f: Frame) {
        val w = canvas.width
        val h = canvas.height
        canvas.drawColor(bgColor)

        val ts = Mercator.TILE_SIZE
        // Center in world pixels at this zoom, shifted by joystick pan
        val cx = Mercator.lngToTileX(f.centerLng, f.zoom) * ts + f.panX
        val cy = Mercator.latToTileY(f.centerLat, f.zoom) * ts + f.panY
        val left = cx - w / 2.0
        val top  = cy - h / 2.0

        // ── Tiles ──
        val txMin = Math.floorDiv(left.toInt(), ts)
        val tyMin = Math.floorDiv(top.toInt(), ts)
        val txMax = Math.floorDiv((left + w).toInt(), ts)
        val tyMax = Math.floorDiv((top + h).toInt(), ts)

        for (tx in txMin..txMax) for (ty in tyMin..tyMax) {
            val bmp = tiles.get(f.zoom, tx, ty) ?: continue
            val dstL = (tx * ts - left).toFloat()
            val dstT = (ty * ts - top).toFloat()
            canvas.drawBitmap(bmp, null, RectF(dstL, dstT, dstL + ts, dstT + ts), tilePaint)
        }

        fun toScreen(lat: Double, lng: Double): Pair<Float, Float> = Pair(
            (Mercator.lngToTileX(lng, f.zoom) * ts - left).toFloat(),
            (Mercator.latToTileY(lat, f.zoom) * ts - top).toFloat(),
        )

        // ── Crow-flies line rider → destination ──
        if (f.riderLat != null && f.riderLng != null && f.destLat != null && f.destLng != null) {
            val (rx, ry) = toScreen(f.riderLat, f.riderLng)
            val (dx, dy) = toScreen(f.destLat, f.destLng)
            canvas.drawPath(Path().apply { moveTo(rx, ry); lineTo(dx, dy) }, linePaint)
        }

        // ── Destination pin ──
        if (f.destLat != null && f.destLng != null) {
            val (dx, dy) = toScreen(f.destLat, f.destLng)
            dotPaint.color = gold
            canvas.drawCircle(dx, dy, 11f, dotPaint)
            dotPaint.color = Color.rgb(26, 20, 2)
            canvas.drawCircle(dx, dy, 4.5f, dotPaint)
        }

        // ── Rider position ──
        if (f.riderLat != null && f.riderLng != null) {
            val (rx, ry) = toScreen(f.riderLat, f.riderLng)
            dotPaint.color = Color.argb(70, 233, 185, 73)
            canvas.drawCircle(rx, ry, 17f, dotPaint)
            dotPaint.color = Color.WHITE
            canvas.drawCircle(rx, ry, 7f, dotPaint)
            dotPaint.color = Color.rgb(20, 22, 24)
            canvas.drawCircle(rx, ry, 3f, dotPaint)
        }

        // ── Top banner ──
        if (f.destName != null) {
            canvas.drawRoundRect(RectF(10f, 8f, w - 10f, 48f), 12f, 12f, bannerPaint)
            val name = if (f.destName.length > 26) f.destName.take(25) + "…" else f.destName
            canvas.drawText(name, 22f, 36f, textPaint)

            if (f.riderLat != null && f.riderLng != null && f.destLat != null && f.destLng != null) {
                val km = Mercator.haversineKm(f.riderLat, f.riderLng, f.destLat, f.destLng)
                val txt = if (km >= 10) "%.0f km".format(km) else "%.1f km".format(km)
                val tw = subTextPaint.measureText(txt)
                canvas.drawText(txt, w - 22f - tw, 36f, subTextPaint)
            }
        }

        // No GPS and no destination → standby frame
        if (f.riderLat == null && f.destLat == null) {
            val msg = "NORTHSTAR · waiting for GPS"
            val bounds = Rect()
            textPaint.getTextBounds(msg, 0, msg.length, bounds)
            canvas.drawText(msg, (w - bounds.width()) / 2f, h / 2f, textPaint)
        }
    }
}
