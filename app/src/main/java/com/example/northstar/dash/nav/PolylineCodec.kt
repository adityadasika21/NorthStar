package com.example.northstar.dash.nav

/** Decoder for the Google/OSRM encoded-polyline format (precision 5 by default). */
object PolylineCodec {
    fun decode(encoded: String, precision: Int = 5): List<GeoPoint> {
        val factor = Math.pow(10.0, precision.toDouble())
        val points = ArrayList<GeoPoint>()
        var index = 0
        var lat = 0
        var lng = 0
        val len = encoded.length

        while (index < len) {
            // latitude delta
            var shift = 0; var result = 0; var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            // longitude delta
            shift = 0; result = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20 && index < len)
            lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1

            points.add(GeoPoint(lat / factor, lng / factor))
        }
        return points
    }
}
