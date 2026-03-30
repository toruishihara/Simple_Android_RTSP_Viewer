package com.github.toruishihara.simple_android_rtsp_viewer.onvif

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.util.Locale
import kotlin.text.Charsets.UTF_8

class DigestAuthenticator(
    private val username: String,
    private val password: String
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val header = response.header("WWW-Authenticate") ?: return null
        if (!header.startsWith("Digest", ignoreCase = true)) return null

        val params = parseDigestHeader(header)

        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        val qop = params["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val opaque = params["opaque"]
        val algorithm = params["algorithm"] ?: "MD5"

        val method = response.request.method
        val uri = response.request.url.encodedPath.let {
            if (response.request.url.encodedQuery != null) "$it?${response.request.url.encodedQuery}" else it
        }

        val nc = "00000001"
        val cnonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")

        val responseDigest = if (qop != null) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }

        val auth = buildString {
            append("""Digest username="$username", realm="$realm", nonce="$nonce", uri="$uri", response="$responseDigest"""")
            if (opaque != null) append(""", opaque="$opaque"""")
            if (qop != null) append(""", qop=$qop, nc=$nc, cnonce="$cnonce"""")
            if (algorithm.isNotBlank()) append(""", algorithm=$algorithm""")
        }

        return response.request.newBuilder()
            .header("Authorization", auth)
            .build()
    }

    private fun parseDigestHeader(header: String): Map<String, String> {
        val value = header.removePrefix("Digest").trim()
        val regex = Regex("""(\w+)=("([^"]*)"|[^,]+)""")
        val result = mutableMapOf<String, String>()
        regex.findAll(value).forEach { m ->
            val key = m.groupValues[1]
            val raw = m.groupValues[2]
            result[key] = raw.trim().trim('"')
        }
        return result
    }

    private fun md5Hex(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(s.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }
}