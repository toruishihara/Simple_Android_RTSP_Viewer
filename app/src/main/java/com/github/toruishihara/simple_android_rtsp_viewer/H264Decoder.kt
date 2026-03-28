package com.github.toruishihara.simple_android_rtsp_viewer

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.github.toruishihara.simple_android_rtsp_viewer.extensions.toHexString
import kotlinx.coroutines.channels.Channel
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class H264Decoder(
    private val outputSurface: Surface
) {
    companion object {
        private const val TAG = "H264D"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
    }

    private var codec: MediaCodec? = null
    private val started = AtomicBoolean(false)

    private var inputIndexChannel: Channel<Int>? = null

    fun init(
        width: Int,
        height: Int,
        sps: ByteArray? = null,
        pps: ByteArray? = null
    ) {
        //release()
        Log.d(TAG, "h.264 decoder init ${width} x ${height} sps size=${sps?.size} pps size=${pps?.size}")
        inputIndexChannel = Channel(Channel.UNLIMITED)
        Log.d(TAG, "inputIndexChannel=${inputIndexChannel}")

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
            // These are optional but useful if you have SPS/PPS from SDP
            if (sps != null) {
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0,0,0,1) + sps))
            }
            if (pps != null) {
                setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0,0,0,1) + pps))
            }

            // Optional low-latency hints; device support varies
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0)
        }

        val c = MediaCodec.createDecoderByType(MIME_TYPE)
        c.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                Log.d(TAG, "onInputBufferAvailable index=${index} calling trySend")
                inputIndexChannel?.trySend(index)
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                Log.d(TAG, "OUTPUT index=$index size=${info.size} pts=${info.presentationTimeUs}")
                try {
                    // Render to Surface if buffer has content
                    val render = info.size > 0
                    codec.releaseOutputBuffer(index, render)
                } catch (e: Exception) {
                    Log.e(TAG, "releaseOutputBuffer failed", e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(TAG, "Decoder error: ${e.diagnosticInfo}", e)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.d(TAG, "Output format changed: $format")
            }
        })

        c.configure(format, outputSurface, null, 0)
        c.start()

        codec = c
        started.set(true)
        Log.d(TAG, "Decoder started")
    }

    /**
     * nal should usually be one full access unit or at least one complete NAL.
     * For many RTSP/H264 cameras, you will need Annex-B start codes (00 00 00 01)
     * unless you feed csd-0/csd-1 separately and your packetizer is correct.
     */
    suspend fun decode(
        nal: ByteArray,
        ptsUs: Long,
        isCodecConfig: Boolean = false,
        isEndOfStream: Boolean = false
    ) {
        Log.d(TAG, "h264 decode len=${nal.size}")
        Log.d(TAG, nal.toHexString(32))

        if (!started.get()) {
            Log.w(TAG, "decoder not started")
            return
        }

        val ch = inputIndexChannel ?: run {
            Log.w(TAG, "inputIndexChannel null")
            return
        }

        val inputIndex = try {
            Log.d(TAG, "ch.receive ch=${ch}")
            ch.receive()
        } catch (e: Exception) {
            Log.w(TAG, "receive failed: ${e.message}")
            return
        }
        Log.d(TAG, "ch.receive done")

        val c = codec ?: run {
            Log.w(TAG, "codec null after receive")
            return
        }

        if (!started.get()) {
            Log.w(TAG, "decoder stopped after receive")
            return
        }

        Log.d(TAG, "inputIndex=${inputIndex}")
        val inputBuffer = try {
            c.getInputBuffer(inputIndex)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "codec already released/stopped") // printed
            return
        } ?: run {
            Log.w(TAG, "Input buffer null")
            return
        }

        Log.d(TAG, "inputBuffer.clear")
        inputBuffer.clear()
        Log.d(TAG, "inputBuffer.put")
        inputBuffer.put(nal)
        Log.d(TAG, "inputBuffer.put end")

        var flags = 0
        if (isCodecConfig) {
            flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
        }
        if (isEndOfStream) {
            flags = flags or MediaCodec.BUFFER_FLAG_END_OF_STREAM
        }

        Log.d(TAG, "queueInputBuffer size=${nal.size} ts=${ptsUs}")
        c.queueInputBuffer(
            inputIndex,
            0,
            nal.size,
            ptsUs,
            flags
        )
    }

    fun release() {
        Log.d(TAG, "TNI release called")
        started.set(false)

        try {
            Log.d(TAG, "inputIndexChannel.close")
            inputIndexChannel?.close()
        } catch (_: Exception) {
        }

        try {
            codec?.stop()
        } catch (_: Exception) {
        }

        try {
            codec?.release()
        } catch (_: Exception) {
        }

        codec = null
        Log.d(TAG, "Decoder released")
    }
}