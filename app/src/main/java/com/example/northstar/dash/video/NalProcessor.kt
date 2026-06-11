package com.example.northstar.dash.video

/**
 * Splits Annex-B H.264 output from MediaCodec into individual NAL units,
 * handles the dash-specific IDR bundling requirement, and filters NAL types
 * the dash rejects (SEI, AUD).
 *
 * Dash-specific rules (from better-dash analysis):
 *  - SPS (type 7) and PPS (type 8): cache, do NOT send raw
 *  - IDR (type 5): prepend cached SPS + PPS with Annex-B start codes, send bundle
 *  - SEI (type 6) and AUD (type 9): discard
 *  - All other slices (types 1–4): send as-is
 */
class NalProcessor(private val onNal: (ByteArray, Boolean) -> Unit) {
    private val START_CODE_4 = byteArrayOf(0, 0, 0, 1)

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun process(annexB: ByteArray) {
        for (nal in split(annexB)) {
            if (nal.isEmpty()) continue
            when (val type = nal[0].toInt() and 0x1F) {
                7    -> sps = nal
                8    -> pps = nal
                5    -> emitIdr(nal)
                6, 9 -> Unit // SEI, AUD — discard
                else -> if (type in 1..4 || type in 10..12) onNal(nal, false)
            }
        }
    }

    private fun emitIdr(idr: ByteArray) {
        val s = sps; val p = pps
        val nal = if (s != null && p != null) {
            s + START_CODE_4 + p + START_CODE_4 + idr
        } else idr
        onNal(nal, true)
    }

    /** Split Annex-B stream on 4-byte (0x00000001) or 3-byte (0x000001) start codes. */
    private fun split(data: ByteArray): List<ByteArray> {
        val nals = mutableListOf<ByteArray>()
        var start = -1
        var i = 0
        while (i < data.size) {
            val sc4 = i + 3 < data.size &&
                data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
            val sc3 = !sc4 && i + 2 < data.size &&
                data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                data[i+2] == 1.toByte()
            when {
                sc4 -> { if (start >= 0) nals += data.copyOfRange(start, i); start = i + 4; i += 4 }
                sc3 -> { if (start >= 0) nals += data.copyOfRange(start, i); start = i + 3; i += 3 }
                else -> i++
            }
        }
        if (start in 0 until data.size) nals += data.copyOfRange(start, data.size)
        return nals
    }
}
