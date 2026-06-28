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
 * 526 × 300, Baseline L4.1, 4-second IDR interval.
 *
 * Accommodates dynamic framerate adjustments requested via user settings 
 * to fine-tune performance and mitigate hardware decoder overruns on the bike's dash.
 *
 * @param onEncodedData Called with (annexBBytes, isKeyFrame) for each output buffer.
 */
class DashEncoder(private val onEncodedData: (ByteArray, Boolean) -> Unit) {
    companion object {
        const val WIDTH   = 526
        const val HEIGHT  = 300
        
        /**
         * Dynamic target framerate (FPS hint) controlled via UI settings.
         * Default fallback is set to a safe intermediate value of 6 FPS.
         * Exposed as a volatile JvmField to allow safe, instant cross-thread updates.
         */
        @Volatile
        @JvmField
        var CONFIG_FPS    = 6 
        
        const val BITRATE = 250_000 
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
            
            // Set dynamic framerate hint from user configurations
            setInteger(MediaFormat.KEY_FRAME_RATE, CONFIG_FPS)
            
            // Optimization 1: Increase I-Frame interval to 4 seconds to eliminate 
            // massive data spikes that trigger dash decoder timeouts over Wi-Fi.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4)
            
            // Optimization 2: Force Constant Bitrate (CBR) mode to keep the data stream flat
            // and predictable during sharp map rotations/turns.
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            
            // Optimization 3: Enforce zero-latency sub-frame hardware behavior on Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }

        val name = selectHardwareEncoder(format)
        codec = (if (name != null) MediaCodec.createByCodecName(name)
                 else MediaCodec.createEncoderByType(MIME)).also { c ->
            Log.i(TAG, "Encoder initialized with Senior optimizations: ${c.name}")
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
        }
    }

    /** Find a hardware AVC encoder that supports this format; null → let the OS pick. */
    private fun selectHardwareEncoder(format: MediaFormat): String? {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (info in list.codecInfos) {
                if (!info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(MIME, true) }) continue
                if (info.isHardwareAccelerated && !info.isSoftwareOnly) {
                    return info.name
                }
            }
        }
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
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx) ?: run {
                        codec.releaseOutputBuffer(idx, false); continue
                    }
                    val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
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

    /** Change the target bitrate live, without rebuilding the encoder. */
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