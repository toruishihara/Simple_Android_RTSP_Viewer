package com.github.toruishihara.simple_android_rtsp_viewer.extensions

fun ByteArray.toHexStringMax(): String {
    return joinToString(" ") { "%02X".format(it) }
}

fun ByteArray.toHexString(len: Int = 32): String {
    val size = minOf(len, this.size)
    return take(size).joinToString(" ") { "%02X".format(it) }
}