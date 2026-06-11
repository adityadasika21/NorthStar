package com.example.northstar.dash.video

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface

/**
 * MediaCodec H.264 encoder matched to the Tripper Dash stream parameters:
 *   526 × 300, 4 fps, ~200 kbps, Baseline L4.1, 1-second IDR interval.
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
        const val FPS     = 4
        const val BITRATE = 204_800
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DRAIN_TIMEOUT_US = 10_000L
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
        codec = MediaCodec.createEncoderByType(MIME).also { c ->
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = c.createInputSurface()
            c.start()
        }
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
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKey    = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)    != 0
                    if (!isConfig && info.size > 0) {
                        val data = ByteArray(info.size).also { buf.get(it) }
                        onEncodedData(data, isKey)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
        }
    }

    fun release() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        inputSurface?.release(); inputSurface = null
    }
}
