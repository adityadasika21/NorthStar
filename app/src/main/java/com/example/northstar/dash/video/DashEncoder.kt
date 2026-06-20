package com.example.northstar.dash.video

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * MediaCodec H.264 encoder for the Tripper Dash stream:
 *   526 × 300, Baseline L4.1, 1-second IDR interval.
 *
 * Cadence/bitrate are DRIVEN BY THE FRAME LOOP, not fixed here — see DashViewModel's motion-adaptive
 * pacing. This mirrors what the official RE Prime app actually does (confirmed by decompiling
 * com.royalenfield.reprime, 2026-06): it projects the map at only **2–4 fps / 100–200 kbps**, with
 * two profiles it switches between. A map doesn't need video frame rates, and crucially a few fps
 * NEVER overruns the dash decoder — which is the real cause of the blink/stutter we kept fighting at
 * 24 fps. So we match RE's proven-stable envelope: configure at the ACTIVE profile (4 fps hint,
 * ~200 kbps) and drop the bitrate live via [requestBitrate] when the map goes static. ([FPS] here is
 * only the encoder's frame-rate HINT; the real rate is the surface feed rate.)
 *
 * History (kept so we don't re-walk it): we'd climbed 4 → 12 → 24 fps and 0.8 → 1.2 Mbps chasing
 * "smoother", but the decompile shows RE deliberately stays LOW. Reverting to that envelope is the fix.
 *
 * Frames are drawn with Android Canvas via the input Surface's hardware
 * canvas — call [renderFrame] with a draw lambda, then [drain] to pull
 * encoded NAL data out.
 *
 * @param onEncodedData  called with (annexBBytes, isKeyFrame) for each output buffer.
 */
class DashEncoder(private val onEncodedData: (ByteArray, Boolean) -> Unit) {
    companion object {
        const val WIDTH   = 526
        const val HEIGHT  = 300
        // Encoder frame-rate HINT only (actual rate = how fast the loop feeds the surface). RE's high
        // profile is 4 fps; we configure to match. The loop paces 4 fps (active) / 2 fps (idle).
        const val FPS     = 4
        // ~200 kbps = RE's high-profile bitrate (decompiled q2g = 204800). The loop drops this to
        // ~100 kbps (RE low profile, q2g = 102400) via requestBitrate() when the map is static.
        // Far under the old 1.2 Mbps that risked overrunning the dash decoder.
        const val BITRATE = 200_000
        const val BITRATE_IDLE = 100_000
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DRAIN_TIMEOUT_US = 10_000L
        private const val TAG = "DashEncoder"
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null

    fun prepare() {
        val format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        // Prefer an explicit HARDWARE AVC encoder — a software fallback runs the
        // CPU hot, which is exactly what this whole project exists to avoid.
        val name = selectHardwareEncoder(format)
        codec = (if (name != null) MediaCodec.createByCodecName(name)
                 else MediaCodec.createEncoderByType(MIME)).also { c ->
            Log.i(TAG, "Encoder: ${c.name}")
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
        }
    }

    /** Find a hardware AVC encoder that supports this format; null → let the OS pick. */
    private fun selectHardwareEncoder(format: MediaFormat): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        // First try true hardware-accelerated encoders (API 29+ flag).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME, true) }) continue
                if (info.isHardwareAccelerated && !info.isSoftwareOnly) {
                    return info.name
                }
            }
        }
        // Fallback: the OS-preferred encoder for this format.
        return runCatching { list.findEncoderForFormat(format) }.getOrNull()
    }

    /** Draw one frame into the encoder via hardware canvas. */
    fun renderFrame(draw: (Canvas) -> Unit) {
        val surface = inputSurface ?: return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            return
        }
        try {
            draw(canvas)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Pull all available encoded buffers; call after every [renderFrame]. */
    fun drain() {
        val codec = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit // SPS/PPS come as CODEC_CONFIG buffer
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx) ?: run {
                        codec.releaseOutputBuffer(idx, false); continue
                    }
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                    // Pass EVERY buffer through, including CODEC_CONFIG (SPS/PPS) — the
                    // NAL processor needs the parameter sets to bundle them with each IDR,
                    // otherwise the dash can't initialise its decoder and times out.
                    if (info.size > 0) {
                        val data = ByteArray(info.size).also { buf.get(it) }
                        onEncodedData(data, isKey)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    /**
     * Change the target bitrate live, without rebuilding the encoder — RE switches between its
     * ~200 kbps (moving) and ~100 kbps (static) profiles this way. MediaCodec applies
     * PARAMETER_KEY_VIDEO_BITRATE to the running session; no IDR or reconfigure needed.
     */
    fun requestBitrate(bps: Int) {
        val c = codec ?: return
        runCatching {
            c.setParameters(android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps)
            })
        }.onFailure { Log.w(TAG, "requestBitrate($bps) failed: ${it.message}") }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release(); inputSurface = null
    }
}
