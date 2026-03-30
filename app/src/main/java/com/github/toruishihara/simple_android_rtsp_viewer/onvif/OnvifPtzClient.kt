package com.github.toruishihara.simple_android_rtsp_viewer.onvif

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class OnvifPtzClient(
    private val config: OnvifConfig
) {
    companion object {
        private const val TAG = "OnvifPtzClient"
        private val XML_MEDIA_TYPE = "application/soap+xml; charset=utf-8".toMediaType()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .authenticator(DigestAuthenticator(config.username, config.password))
        .build()

    suspend fun continuousMove(
        pan: Float,
        tilt: Float,
        zoom: Float = 0f
    ): String {
        val body = buildContinuousMoveSoap(
            profileToken = config.profileToken,
            pan = pan,
            tilt = tilt,
            zoom = zoom,
            username = config.username,
            password = config.password
        )
        return postSoap(body)
    }

    suspend fun stop(
        panTilt: Boolean = true,
        zoom: Boolean = true
    ): String {
        val body = buildStopSoap(
            profileToken = config.profileToken,
            panTilt = panTilt,
            zoom = zoom,
            username = config.username,
            password = config.password
        )
        return postSoap(body)
    }

    private fun postSoap(xml: String): String {
        val request = Request.Builder()
            .url(config.deviceServiceUrl)
            .post(xml.toRequestBody(XML_MEDIA_TYPE))
            .header("Content-Type", "application/soap+xml; charset=utf-8")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Log.e(TAG, "HTTP ${response.code} body=$text")
                throw IOException("ONVIF HTTP error ${response.code}")
            }
            Log.d(TAG, "SOAP response: $text")
            return text
        }
    }
}

data class OnvifConfig(
    val deviceServiceUrl: String,   // e.g. http://192.168.0.120:8899/onvif/device_service
    val username: String,
    val password: String,
    val profileToken: String
)