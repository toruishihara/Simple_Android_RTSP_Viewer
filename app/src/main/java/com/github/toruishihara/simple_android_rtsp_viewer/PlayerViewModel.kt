package com.github.toruishihara.simple_android_rtsp_viewer

import android.util.Log
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.toruishihara.simple_android_rtsp_viewer.extensions.toHexString
import com.github.toruishihara.simple_android_rtsp_viewer.onvif.OnvifConfig
import com.github.toruishihara.simple_android_rtsp_viewer.onvif.OnvifPtzClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PlayerViewModel : ViewModel() {
    companion object {
        private const val TAG = "TNIVM"
        const val RTPPort = 51500
    }
    private var rtspClient: RTSPClient? = null
    private var h264Receiver: RTPH264Receiver? = null
    private var h264Decoder: H264Decoder? = null
    var rtspSurface: Surface? = null
        private set
    fun setSurface(s: Surface?) {
        Log.d(TAG, "setSurface s=${s}")
        rtspSurface = s
    }

    var rtspUrl by mutableStateOf("rtsp://long:short@192.168.0.120:554/live/ch1")

    var isPlaying by mutableStateOf(false)
        private set

    var statusText by mutableStateOf("Idle")
        private set

    fun onUrlChange(newUrl: String) {
        rtspUrl = newUrl
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun start() {
        isPlaying = true
        viewModelScope.launch {
            if (rtspClient == null) {
                rtspClient = RTSPClient(rtspUrl, RTPPort)
            }
            rtspClient?.connect()
            val res = rtspClient?.setupVideo()
            if (res == null) {
                Log.e(TAG, "No SPS error")
                return@launch
            }
            Log.d(TAG, "SPS=${res.sps.toHexString()}")

            val parser = SPSParser()
            val (w, h) = parser.parseSps(res.sps)
            Log.d(TAG, "w=${w} h=${h}")

            initDecoder(w,h, res.sps, res.pps)
            startRtpReceiver(RTPPort)
            val res2 = rtspClient?.playVideo()
        }
    }

    fun stop() {
        isPlaying = false
    }

    fun startRtpReceiver(port: Int) {
        h264Receiver?.release()

        val onNal: (ByteArray, Long) -> Unit = { bytes, ts ->
            //Log.d(TAG, "onNal len=${bytes.size}")
            //Log.d(TAG, bytes.toHexString())
            h264Decoder?.let {
                viewModelScope.launch {
                    h264Decoder!!.decode(bytes, ts)
                }
            }
        }

        val receiver = RTPH264Receiver(port)
        h264Receiver = receiver
        receiver.start(onNal)

        viewModelScope.launch {
            receiver.packetFlow.collect { packet ->
                statusText = "UDP ${packet.length} bytes from ${packet.address.hostAddress}:${packet.port}"

                // Example: feed packet.data into your RTP parser
                // rtpDepacketizer.onUdpPacket(packet.data)
            }
        }
    }

    fun stopRtpReceiver() {
        h264Receiver?.release()
        h264Receiver = null
        statusText = "Stopped"
    }

    fun onSurfaceReady(surface: Surface) {
        rtspSurface = surface
        statusText = "Surface ready"
    }

    fun initDecoder(
        width: Int,
        height: Int,
        sps: ByteArray? = null,
        pps: ByteArray? = null
    ) {
        val surface = rtspSurface
        if (surface == null) {
            statusText = "Surface not ready"
            return
        }

        h264Decoder?.release()
        h264Decoder = H264Decoder(surface)

        try {
            h264Decoder?.init(width, height, sps, pps)
            statusText = "Decoder initialized ${width}x$height"
        } catch (e: Exception) {
            statusText = "Decoder init error: ${e.message}"
        }
    }

    fun releaseDecoder() {
        h264Decoder?.release()
        h264Decoder = null
        statusText = "Decoder released"
    }

    override fun onCleared() {
        super.onCleared()
        releaseDecoder()
        rtspSurface = null
        h264Receiver?.release()
    }

    // ONVIF
    private var onvif: OnvifPtzClient? = null

    fun onvifUp() {
        if (onvif == null) {
            initOnvif()
        }
        moveUp()
    }
    fun onvifDown() {
        if (onvif == null) {
            initOnvif()
        }
        moveDown()
    }
    fun onvifLeft() {
        if (onvif == null) {
            initOnvif()
        }
        moveLeft()
    }
    fun onvifRight() {
        if (onvif == null) {
            initOnvif()
        }
        moveRight()
    }

    fun initOnvif() {
        val uri = java.net.URI(rtspUrl)

        val userInfo = uri.userInfo // "long:short"
        val ip = uri.host         // "192.168.0.120"
        val port = 8899

        val (user, pass) = userInfo.split(":")

        onvif = OnvifPtzClient(
            OnvifConfig(
                deviceServiceUrl = "http://$ip:$port/onvif/device_service",
                username = user,
                password = pass,
                profileToken = "Profile_token1"
            )
        )
    }

    fun moveRight() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                onvif?.continuousMove(pan = 0.5f, tilt = 0f, zoom = 0f)
            } catch (e: Exception) {
                Log.e("CameraVM", "moveRight failed", e)
            }
        }
    }

    fun moveLeft() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                onvif?.continuousMove(pan = -0.5f, tilt = 0f, zoom = 0f)
            } catch (e: Exception) {
                Log.e("CameraVM", "moveLeft failed", e)
            }
        }
    }

    fun moveUp() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                onvif?.continuousMove(pan = 0f, tilt = 0.5f, zoom = 0f)
            } catch (e: Exception) {
                Log.e("CameraVM", "moveUp failed", e)
            }
        }
    }

    fun moveDown() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                onvif?.continuousMove(pan = 0f, tilt = -0.5f, zoom = 0f)
            } catch (e: Exception) {
                Log.e("CameraVM", "moveDown failed", e)
            }
        }
    }

    fun stopMove() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                onvif?.stop()
            } catch (e: Exception) {
                Log.e("CameraVM", "stopMove failed", e)
            }
        }
    }
}

