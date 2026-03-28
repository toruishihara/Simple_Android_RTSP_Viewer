package com.github.toruishihara.simple_android_rtsp_viewer

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

data class LineResponse(
    val header: String,
    val body: ByteArray
)

class TCPClient {
    companion object {
        private const val TAG = "TNITCP"
        const val ERR_NONE = 0
        const val ERR_CONNECT_FAILED = 1
        const val ERR_SEND_FAILED = 2
        const val ERR_RECEIVE_TIMEOUT = 3
        const val ERR_RECEIVE_FAILED = 4
        const val ERR_CLOSED = 5
        const val ERR_PARSE_FAILED = 6
    }

    @Volatile
    var errorNum: Int = ERR_NONE
        private set

    @Volatile
    var errorMessage: String? = null
        private set

    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null

    // Keep unread bytes between calls
    private val recvBuffer = ArrayList<Byte>()

    suspend fun connect(
        host: String,
        port: Int,
        connectTimeoutMs: Int = 5000
    ): Boolean = withContext(Dispatchers.IO) {
        closeInternal()

        try {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
            input = BufferedInputStream(s.getInputStream())
            output = BufferedOutputStream(s.getOutputStream())
            setError(ERR_NONE, null)
            true
        } catch (e: IOException) {
            setError(ERR_CONNECT_FAILED, "connect failed: ${e.message}")
            false
        }
    }

    suspend fun send(text: String): Boolean = withContext(Dispatchers.IO) {
        val out = output ?: run {
            setError(ERR_SEND_FAILED, "not connected")
            return@withContext false
        }

        try {
            Log.d(TAG, text)
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (e: IOException) {
            setError(ERR_SEND_FAILED, "send failed: ${e.message}")
            false
        }
    }

    suspend fun readLineResponse(timeoutMs: Int = 5000): LineResponse? =
        withContext(Dispatchers.IO) {
            try {
                val headerBytes = readUntilDelimiter("\r\n\r\n".toByteArray(), timeoutMs)
                    ?: return@withContext null

                val header = headerBytes.toString(Charsets.UTF_8)

                val contentLength = parseContentLength(header)
                val body = if (contentLength > 0) {
                    readExact(contentLength, timeoutMs) ?: return@withContext null
                } else {
                    ByteArray(0)
                }
                Log.d(TAG, "res header=${header}")

                setError(ERR_NONE, null)
                LineResponse(header, body)
            } catch (e: Exception) {
                Log.d(TAG, "readLineResponse error:${e.message}")
                setError(ERR_PARSE_FAILED, "RTSP parse failed: ${e.message}")
                null
            }
        }

    fun close() {
        closeInternal()
        setError(ERR_CLOSED, "closed")
    }

    private suspend fun readUntilDelimiter(
        delimiter: ByteArray,
        timeoutMs: Int
    ): ByteArray? {
        while (true) {
            val idx = indexOfDelimiter(recvBuffer, delimiter)
            if (idx >= 0) {
                val result = recvBuffer.subList(0, idx + delimiter.size).toByteArray()
                repeat(idx + delimiter.size) { recvBuffer.removeAt(0) }
                return result
            }

            val chunk = readSome(timeoutMs) ?: return null
            recvBuffer.addAll(chunk.toList())
        }
    }

    private suspend fun readExact(
        length: Int,
        timeoutMs: Int
    ): ByteArray? {
        while (recvBuffer.size < length) {
            val chunk = readSome(timeoutMs) ?: return null
            recvBuffer.addAll(chunk.toList())
        }

        val result = recvBuffer.subList(0, length).toByteArray()
        repeat(length) { recvBuffer.removeAt(0) }
        return result
    }

    private suspend fun readSome(timeoutMs: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val s = socket ?: run {
                setError(ERR_RECEIVE_FAILED, "not connected")
                return@withContext null
            }

            val ins = input ?: run {
                setError(ERR_RECEIVE_FAILED, "input stream is null")
                return@withContext null
            }

            val oldTimeout = s.soTimeout
            try {
                s.soTimeout = timeoutMs
                val buf = ByteArray(4096)
                val len = ins.read(buf)

                if (len < 0) {
                    setError(ERR_CLOSED, "remote closed")
                    closeInternal()
                    return@withContext null
                }

                buf.copyOf(len)
            } catch (e: SocketTimeoutException) {
                setError(ERR_RECEIVE_TIMEOUT, "receive timeout")
                null
            } catch (e: IOException) {
                setError(ERR_RECEIVE_FAILED, "receive failed: ${e.message}")
                null
            } finally {
                try {
                    s.soTimeout = oldTimeout
                } catch (_: Exception) {
                }
            }
        }

    private fun parseContentLength(header: String): Int {
        val line = header.lines()
            .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?: return 0

        return line.substringAfter(":").trim().toIntOrNull() ?: 0
    }

    private fun indexOfDelimiter(buffer: List<Byte>, delimiter: ByteArray): Int {
        if (buffer.size < delimiter.size) return -1

        for (i in 0..buffer.size - delimiter.size) {
            var matched = true
            for (j in delimiter.indices) {
                if (buffer[i + j] != delimiter[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return i
        }
        return -1
    }

    private fun closeInternal() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}

        input = null
        output = null
        socket = null
        recvBuffer.clear()
    }

    private fun setError(code: Int, message: String?) {
        if (code != 0) {
            Log.d(TAG, "setError ${code} ${message}")
        }
        errorNum = code
        errorMessage = message
    }
}