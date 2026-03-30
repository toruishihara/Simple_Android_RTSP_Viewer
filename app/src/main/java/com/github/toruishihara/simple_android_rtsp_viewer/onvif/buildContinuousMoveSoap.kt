package com.github.toruishihara.simple_android_rtsp_viewer.onvif

import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlin.text.Charsets.UTF_8

fun buildContinuousMoveSoap(
    profileToken: String,
    pan: Float,
    tilt: Float,
    zoom: Float,
    username: String,
    password: String
): String {
    val wsse = buildWsSecurityHeader(username, password)

    return """
        |<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
        |            xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"
        |            xmlns:tt="http://www.onvif.org/ver10/schema"
        |            xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        |            xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
        |  <s:Header>
        |    $wsse
        |  </s:Header>
        |  <s:Body>
        |    <tptz:ContinuousMove>
        |      <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
        |      <tptz:Velocity>
        |        <tt:PanTilt x="$pan" y="$tilt"/>
        |        <tt:Zoom x="$zoom"/>
        |      </tptz:Velocity>
        |    </tptz:ContinuousMove>
        |  </s:Body>
        |</s:Envelope>
    """.trimMargin()
}

fun buildStopSoap(
    profileToken: String,
    panTilt: Boolean,
    zoom: Boolean,
    username: String,
    password: String
): String {
    val wsse = buildWsSecurityHeader(username, password)

    return """
        |<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"
        |            xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl"
        |            xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        |            xmlns:wsu="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">
        |  <s:Header>
        |    $wsse
        |  </s:Header>
        |  <s:Body>
        |    <tptz:Stop>
        |      <tptz:ProfileToken>$profileToken</tptz:ProfileToken>
        |      <tptz:PanTilt>$panTilt</tptz:PanTilt>
        |      <tptz:Zoom>$zoom</tptz:Zoom>
        |    </tptz:Stop>
        |  </s:Body>
        |</s:Envelope>
    """.trimMargin()
}

private fun buildWsSecurityHeader(username: String, password: String): String {
    val nonceBytes = UUID.randomUUID().toString().toByteArray(UTF_8)
    val nonceB64 = Base64.getEncoder().encodeToString(nonceBytes)
    val created = DateTimeFormatter.ISO_INSTANT.format(Instant.now())

    val digestInput = nonceBytes + created.toByteArray(UTF_8) + password.toByteArray(UTF_8)
    val sha1 = MessageDigest.getInstance("SHA-1").digest(digestInput)
    val passwordDigest = Base64.getEncoder().encodeToString(sha1)

    return """
        |<wsse:Security s:mustUnderstand="1">
        |  <wsse:UsernameToken>
        |    <wsse:Username>$username</wsse:Username>
        |    <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$passwordDigest</wsse:Password>
        |    <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</wsse:Nonce>
        |    <wsu:Created>$created</wsu:Created>
        |  </wsse:UsernameToken>
        |</wsse:Security>
    """.trimMargin()
}