package com.github.toruishihara.simple_android_rtsp_viewer

import android.net.Uri
import android.util.Log
import java.security.MessageDigest
import java.util.Locale

class RTSPClient(
    urlString: String,
    private val rtpPort: Int,
    private val onState: ((State) -> Unit)? = null
) {
    companion object {
        private const val TAG = "TNIRTSP"
    }
    private var client: TCPClient

    private var urlStr = urlString
    private var userAgent = "LibVLC/3"
    private var host = ""
    private var port: Int = 554
    private var path: String = "/"
    private var user = ""
    private var pass = ""
    private var realm = ""
    private var nonce = ""
    private var sessionID = ""

    private val rtcpPort: Int = rtpPort + 1

    private val rtspURLNoCreds: String
        get() = "rtsp://$host:$port$path"

    var state: State = State.IDLE
        private set

    private var needAuth = false
    private var cseq = 1
    var hasH264 = false
        private set
    var hasMJPEG = false
        private set

    var sps: ByteArray = byteArrayOf()
        private set
    var pps: ByteArray = byteArrayOf()
        private set

    enum class State {
        IDLE,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    class RTSPException(message: String) : Exception(message)

    init {
        val uri = Uri.parse(urlString)
        host = uri.host ?: ""
        port = if (uri.port != -1) uri.port else 554
        path = if (uri.path.isNullOrEmpty()) "/" else uri.path ?: "/"
        user = uri.userInfo?.substringBefore(":") ?: ""
        pass = uri.userInfo?.substringAfter(":", "") ?: ""

        client = TCPClient()
    }

    private fun setState(newState: State) {
        state = newState
        onState?.invoke(newState)
    }

    suspend fun connect() {
        if (state != State.IDLE) return

        setState(State.CONNECTING)

        val ok = client.connect(host, port)
        if (ok) {
            setState(State.CONNECTED)
        } else {
            setState(State.ERROR)
            throw RTSPException("TCP connect failed: ${client.errorMessage}")
        }
    }

    fun disconnect() {
        client.close()
        setState(State.IDLE)
    }

    suspend fun setupVideo(): SetupResult {
        try {
            val req0 = buildString {
                append("OPTIONS $urlStr RTSP/1.0\r\n")
                append("CSeq: $cseq\r\n")
                append("User-Agent: $userAgent\r\n")
                append("\r\n")
            }

            client.send(req0)
            val res0 = client.readLineResponse()

            if (res0 == null) {
                return SetupResult(false, false, byteArrayOf(), byteArrayOf())
            }
            if (!res0.header.contains("200 OK") ||
                !res0.header.contains("DESCRIBE") ||
                !res0.header.contains("PLAY")
            ) {
                return SetupResult(false, false, byteArrayOf(), byteArrayOf())
            }

            cseq += 1
            val req1 = buildString {
                append("DESCRIBE $urlStr RTSP/1.0\r\n")
                append("CSeq: $cseq\r\n")
                append("User-Agent: $userAgent\r\n")
                append("Accept: application/sdp\r\n")
                append("\r\n")
            }

            client.send(req1)
            val res1 = client.readLineResponse()
            if (res1 == null) {
                return SetupResult(false, false, byteArrayOf(), byteArrayOf())
            }

            if (res1.header.contains("200 OK")) {
                needAuth = false
            } else if (res1.header.contains("401")) {
                needAuth = true
                val params = parseDigestAuth(res1.header)
                realm = params.realm ?: return SetupResult(false, false, byteArrayOf(), byteArrayOf())
                nonce = params.nonce ?: return SetupResult(false, false, byteArrayOf(), byteArrayOf())
            }

            val res2 = sendAuthDescribe()
            Log.d(TAG, "res2.h=${res2?.header}")
            if (res2 == null) {
                Log.d(TAG, "res2=null")
                return SetupResult(false, false, byteArrayOf(), byteArrayOf())
            }
            val body2 = res2.body.toString(Charsets.UTF_8)
            Log.d(TAG, "res2.body=${body2}")

            hasH264 = false
            hasMJPEG = false
            sps = byteArrayOf()
            pps = byteArrayOf()

            val lines = body2.split("\r\n")
            for (line in lines) {
                when {
                    line.startsWith("m=video 0 RTP/AVP 96") -> {
                        hasH264 = true
                    }

                    line.startsWith("a=fmtp:96") -> {
                        val parsed = parseSpropParameterSets(line)
                        sps = parsed.first
                        pps = parsed.second
                    }

                    line.startsWith("m=video 0 RTP/AVP 26") -> {
                        hasMJPEG = true
                    }
                }
            }

            return SetupResult(hasH264, hasMJPEG, sps, pps)
        } catch (e: Exception) {
            setState(State.ERROR)
            throw e
        }
    }

    suspend fun sendAuthDescribe(): LineResponse? {
        cseq += 1
        val digest = digestLine("DESCRIBE", urlStr)

        val req = buildString {
            append("DESCRIBE $urlStr RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append(digest)
            append("User-Agent: $userAgent\r\n")
            append("Accept: application/sdp\r\n")
            append("\r\n")
        }

        client.send(req)
        return client.readLineResponse()
    }

    suspend fun playVideo() {
        cseq += 1
        var auth = ""
        if (needAuth) {
            auth = digestLine("SETUP", urlStr)
        }

        val req3 = buildString {
            append("SETUP $urlStr/track0 RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append(auth)
            append("User-Agent: $userAgent\r\n")
            append("Transport: RTP/AVP;unicast;client_port=$rtpPort-$rtcpPort\r\n")
            append("\r\n")
        }

        client.send(req3)
        val res3 = client.readLineResponse()
        if (res3 == null) {
            throw RTSPException("res3 is null")
        }
        sessionID = parseSessionID(res3.header)

        if (sessionID.isEmpty()) {
            throw RTSPException("Session ID missing")
        }

        cseq += 1
        auth = if (needAuth) digestLine("PLAY", urlStr) else ""

        val req4 = buildString {
            append("PLAY $urlStr RTSP/1.0\r\n")
            append("CSeq: $cseq\r\n")
            append(auth)
            append("User-Agent: $userAgent\r\n")
            append("Session: $sessionID\r\n")
            append("Range: npt=0.000-\r\n")
            append("\r\n")
        }

        client.send(req4)
        client.readLineResponse()
    }

    data class DigestAuthParams(
        val realm: String?,
        val nonce: String?
    )

    fun parseDigestAuth(header: String): DigestAuthParams {
        val regex = Regex("""realm="([^"]+)",\s*nonce="([^"]+)"""")
        val match = regex.find(header)
        return if (match != null) {
            DigestAuthParams(
                realm = match.groupValues.getOrNull(1),
                nonce = match.groupValues.getOrNull(2)
            )
        } else {
            DigestAuthParams(null, null)
        }
    }

    fun parseContentLength(headerText: String): Int {
        val lines = headerText.split("\r\n")
        for (line in lines) {
            if (line.lowercase(Locale.US).startsWith("content-length:")) {
                return line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }

    fun parseSessionID(response: String): String {
        val lines = response.split("\r\n")
        for (line in lines) {
            if (line.startsWith("Session:")) {
                return line
                    .removePrefix("Session:")
                    .trim()
                    .substringBefore(";")
            }
        }
        return ""
    }

    fun digestLine(method: String, uri: String): String {
        val ha1 = md5Hex("$user:$realm:$pass")
        val ha2 = md5Hex("$method:$uri")
        val hash = md5Hex("$ha1:$nonce:$ha2")

        return buildString {
            append("""Authorization: Digest username="$user", realm="$realm", nonce="$nonce", uri="$urlStr", response="$hash"""")
            append("\r\n")
        }
    }

    class SDPParseException(message: String) : Exception(message)

    @OptIn(ExperimentalStdlibApi::class)
    fun parseSpropParameterSets(line: String): Pair<ByteArray, ByteArray> {
        val key = "sprop-parameter-sets="
        val start = line.indexOf(key)
        if (start < 0) throw SDPParseException("No sprop-parameter-sets")

        var tail = line.substring(start + key.length)
        val semi = tail.indexOf(';')
        if (semi >= 0) {
            tail = tail.substring(0, semi)
        }

        val parts = tail.split(",")
        if (parts.size < 2) {
            throw SDPParseException("Invalid sprop-parameter-sets")
        }

        fun decodeBase64WithPadding(s: String): ByteArray {
            var str = s
            val rem = str.length % 4
            if (rem != 0) {
                str += "=".repeat(4 - rem)
            }
            return android.util.Base64.decode(str, android.util.Base64.DEFAULT)
        }

        val spsData = decodeBase64WithPadding(parts[0])
        val ppsData = decodeBase64WithPadding(parts[1])
        Log.d(TAG, "SPS=${spsData.toHexString()}")
        Log.d(TAG, "PPS=${ppsData.toHexString()}")
        return Pair(spsData, ppsData)
    }

    private fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class SetupResult(
        val hasH264: Boolean,
        val hasMJPEG: Boolean,
        val sps: ByteArray,
        val pps: ByteArray
    )
}

//fun ByteArray.toHexString(): String {
//    return joinToString(" ") { "%02X".format(it) }
//}