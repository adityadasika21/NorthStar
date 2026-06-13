package com.example.northstar.dash.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.example.northstar.dash.nav.GeoPoint

/**
 * Draws the navigation frame for the Tripper Dash (526 × 300).
 *
 * Layers: OSM tiles (already dark-filtered by TileProvider) → road route polyline
 * → destination pin → rider marker → top banner (name + remaining) → maneuver chip.
 * Optional heading-up rotation. Paint/Path/Rect objects are reused across frames
 * to avoid per-frame allocation churn.
 */
class MapRenderer(private val tiles: TileProvider) {

    data class Frame(
        val centerLat: Double,
        val centerLng: Double,
        val zoom: Int,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val headingUp: Boolean = false,
        val heading: Float = 0f,           // travel bearing, degrees
        val riderLat: Double? = null,
        val riderLng: Double? = null,
        val destLat: Double? = null,
        val destLng: Double? = null,
        val destName: String? = null,
        val route: List<GeoPoint> = emptyList(),
        val maneuverText: String? = null,  // e.g. "Turn left · 400 m"
        val remainingText: String? = null, // e.g. "186 km"
    )

    private val bgColor   = Color.rgb(229, 227, 223) // Google Maps land colour, behind missing tiles
    private val routeBlue = Color.rgb(66, 133, 244)  // Google Maps directions blue (#4285F4)
    private val googleRed = Color.rgb(234, 67, 53)   // Google destination pin red (#EA4335)

    private val tilePaint  = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Google tiles already carry the right colours/contrast — only a gentle
        // saturation nudge to help against the dash TFT's daylight wash-out. No
        // brightness/contrast tricks (those flattened or clipped the map before).
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.2f) })
    }
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 11f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = routeBlue; style = Paint.Style.STROKE
        strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; isFakeBoldText = true }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = routeBlue; textSize = 19f; isFakeBoldText = true }
    private val bannerPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 13, 15, 17) }
    private val standbyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 64, 67); textSize = 22f; isFakeBoldText = true }

    // Reused across frames
    private val routePath = Path()
    private val riderPath = Path()
    private val tmpRect = RectF()
    private val textBounds = Rect()

    fun draw(canvas: Canvas, f: Frame) {
        val w = canvas.width
        val h = canvas.height
        canvas.drawColor(bgColor)

        val rotate = f.headingUp
        // Nav view: bias the rider toward the lower third so the road AHEAD fills the
        // screen (like Google Maps navigation). North-up view keeps the rider centred.
        val pivotY = if (rotate) h * 0.66f else h / 2f

        val ts = Mercator.TILE_SIZE
        val cx = Mercator.lngToTileX(f.centerLng, f.zoom) * ts + f.panX
        val cy = Mercator.latToTileY(f.centerLat, f.zoom) * ts + f.panY
        val left = cx - w / 2.0
        val top  = cy - pivotY

        fun sx(lng: Double) = (Mercator.lngToTileX(lng, f.zoom) * ts - left).toFloat()
        fun sy(lat: Double) = (Mercator.latToTileY(lat, f.zoom) * ts - top).toFloat()

        if (rotate) {
            canvas.save()
            canvas.rotate(-f.heading, w / 2f, pivotY)
        }

        // ── Tiles (padded when rotating so corners are covered) ──
        val pad = if (rotate) (maxOf(w, h) * 0.45).toInt() else 0
        val txMin = Math.floorDiv((left - pad).toInt(), ts)
        val tyMin = Math.floorDiv((top - pad).toInt(), ts)
        val txMax = Math.floorDiv((left + w + pad).toInt(), ts)
        val tyMax = Math.floorDiv((top + h + pad).toInt(), ts)
        for (tx in txMin..txMax) for (ty in tyMin..tyMax) {
            val bmp = tiles.get(f.zoom, tx, ty) ?: continue
            val dstL = (tx * ts - left).toFloat()
            val dstT = (ty * ts - top).toFloat()
            tmpRect.set(dstL, dstT, dstL + ts, dstT + ts)
            canvas.drawBitmap(bmp, null, tmpRect, tilePaint)
        }

        // ── Road route polyline ──
        if (f.route.size >= 2) {
            routePath.reset()
            routePath.moveTo(sx(f.route[0].lng), sy(f.route[0].lat))
            for (i in 1 until f.route.size) routePath.lineTo(sx(f.route[i].lng), sy(f.route[i].lat))
            canvas.drawPath(routePath, routeCasing)
            canvas.drawPath(routePath, routePaint)
        }

        // ── Destination pin ──
        if (f.destLat != null && f.destLng != null) {
            val dx = sx(f.destLng); val dy = sy(f.destLat)
            // Google-style red destination pin (white ring + red fill).
            dotPaint.color = Color.WHITE; canvas.drawCircle(dx, dy, 12f, dotPaint)
            dotPaint.color = googleRed; canvas.drawCircle(dx, dy, 9f, dotPaint)
            dotPaint.color = Color.WHITE; canvas.drawCircle(dx, dy, 3.5f, dotPaint)
        }

        // ── Rider marker (Google blue) ──
        if (f.riderLat != null && f.riderLng != null) {
            val rx = sx(f.riderLng); val ry = sy(f.riderLat)
            dotPaint.color = Color.argb(60, 66, 133, 244); canvas.drawCircle(rx, ry, 17f, dotPaint)
            if (rotate) {
                // Heading-up: blue chevron pointing up (travel direction)
                riderPath.reset()
                riderPath.moveTo(rx, ry - 11f)
                riderPath.lineTo(rx - 7f, ry + 7f)
                riderPath.lineTo(rx + 7f, ry + 7f)
                riderPath.close()
                canvas.save(); canvas.rotate(f.heading, rx, ry)
                dotPaint.color = Color.WHITE; canvas.drawCircle(rx, ry, 9f, dotPaint)
                dotPaint.color = routeBlue; canvas.drawPath(riderPath, dotPaint)
                canvas.restore()
            } else {
                dotPaint.color = Color.WHITE; canvas.drawCircle(rx, ry, 8f, dotPaint)
                dotPaint.color = routeBlue; canvas.drawCircle(rx, ry, 5.5f, dotPaint)
            }
        }

        if (rotate) canvas.restore()

        // No on-map text overlays — the dash's own widgets show name/distance/turn,
        // and the round bezel clips anything near the top edge.

        // ── Standby when nothing to show (dark text on the light map bg) ──
        if (f.riderLat == null && f.destLat == null) {
            val msg = "NORTHSTAR · waiting for GPS"
            standbyPaint.getTextBounds(msg, 0, msg.length, textBounds)
            canvas.drawText(msg, (w - textBounds.width()) / 2f, h / 2f, standbyPaint)
        }
    }
}
