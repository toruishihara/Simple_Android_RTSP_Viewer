package com.github.toruishihara.simple_android_rtsp_viewer

import android.util.Log
import com.github.toruishihara.simple_android_rtsp_viewer.extensions.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

data class UdpPacketData(
    val data: ByteArray,
    val length: Int,
    val address: InetAddress,
    val port: Int
)

class RTPH264Receiver(
    private val listenPort: Int,
    private val soTimeoutMs: Int = 1000
) {
    companion object {
        private const val TAG = "TNIRTP"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var receiveJob: Job? = null
    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)

    private val _packetFlow = MutableSharedFlow<UdpPacketData>(extraBufferCapacity = 64)
    val packetFlow: SharedFlow<UdpPacketData> = _packetFlow

    // H264 FU-A variables
    private var currentTimestamp: UInt? = null
    private val fuBuffer = ArrayList<Byte>()
    private var fuActive = false
    private var sCnt = 0

    @Volatile
    var lastError: String? = null
        private set

    fun start(
        onNal: (ByteArray,Long) -> Unit
    ) {
        if (running.get()) return

        try {
            val s = DatagramSocket(listenPort)
            s.soTimeout = soTimeoutMs
            socket = s
            running.set(true)

            receiveJob = scope.launch {
                Log.d(TAG, "UDP receiver started on port=$listenPort")

                val buffer = ByteArray(2048)

                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        s.receive(packet)

                        val packetBytes = packet.data.copyOfRange(0, packet.length)
                        //Log.d(TAG, "UDP len=${packetBytes.size}")
                        handleRtpPacket(packetBytes, onNal)
                        //Log.d(TAG, packetBytes.toHexString())
                        /*
                        _packetFlow.tryEmit(
                            UdpPacketData(
                                data = packetBytes,
                                length = packet.length,
                                address = packet.address,
                                port = packet.port
                            )
                        )
                         */
                    } catch (_: SocketTimeoutException) {
                        // normal timeout to let loop check running flag
                    } catch (e: SocketException) {
                        if (running.get()) {
                            lastError = "Socket error: ${e.message}"
                            Log.e(TAG, lastError ?: "Socket error", e)
                        }
                        break
                    } catch (e: IOException) {
                        if (running.get()) {
                            lastError = "Receive error: ${e.message}"
                            Log.e(TAG, lastError ?: "Receive error", e)
                        }
                    }
                }

                Log.d(TAG, "UDP receiver loop ended")
            }
        } catch (e: Exception) {
            lastError = "Start failed: ${e.message}"
            Log.e(TAG, lastError ?: "Start failed", e)
        }
    }

    fun stop() {
        running.set(false)

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        socket = null
        receiveJob?.cancel()
        receiveJob = null

        Log.d(TAG, "UDP receiver stopped")
    }

    fun release() {
        stop()
        scope.cancel()
    }

    // MARK: - RTP + H264 (FU-A only)
    private fun handleRtpPacket(
        pkt: ByteArray,
        onNal: (ByteArray, Long) -> Unit
    ) {
        // RTP header: min 12 bytes
        if (pkt.size < 12) return

        val b0 = pkt[0].toInt() and 0xFF
        val version = b0 shr 6
        if (version != 2) return

        val hasPadding = (b0 and 0x20) != 0
        val hasExt = (b0 and 0x10) != 0
        val csrcCount = b0 and 0x0F

        val b1 = pkt[1].toInt() and 0xFF
        val marker = (b1 and 0x80) != 0
        val payloadType = b1 and 0x7F
        // marker / payloadType kept for debugging if needed
        // Log.d(TAG, "marker=$marker payloadType=$payloadType")

        var offset = 12 + (4 * csrcCount)
        if (pkt.size < offset) return

        // RTP extension header
        if (hasExt) {
            if (pkt.size < offset + 4) return
            val extLenWords =
                ((pkt[offset + 2].toInt() and 0xFF) shl 8) or
                        (pkt[offset + 3].toInt() and 0xFF)
            offset += 4 + extLenWords * 4
            if (pkt.size < offset) return
        }

        // Padding
        var end = pkt.size
        if (hasPadding) {
            val padLen = pkt[pkt.size - 1].toInt() and 0xFF
            if (padLen > 0 && padLen <= end) {
                end -= padLen
            }
        }

        // Timestamp bytes 4..7
        val ts = (
                ((pkt[4].toLong() and 0xFF) shl 24) or
                        ((pkt[5].toLong() and 0xFF) shl 16) or
                        ((pkt[6].toLong() and 0xFF) shl 8) or
                        (pkt[7].toLong() and 0xFF)
                ).toUInt()

        // Flush access unit boundary on timestamp change
        if (currentTimestamp != null && currentTimestamp != ts) {
            fuBuffer.clear()
            //Log.d(TAG, "fuActive=false")
            fuActive = false
        }
        currentTimestamp = ts

        if (end <= offset) return
        val payload = pkt.copyOfRange(offset, end)

        // H264 payload
        if (payload.size < 2) return

        if (sCnt < 32) {
            Log.d(TAG, "RTP payload size=${payload.size}")
            //Log.d(TAG, payload.toHexString())
            sCnt += 1
        }

        val nal0 = payload[0].toInt() and 0xFF
        val nalType = nal0 and 0x1F

        Log.d(TAG, "payload[0]=%02X nalType=%02X %d".format(nal0, nalType, nalType))

        if (nalType != 28) {
            // Not FU-A
            when (nalType) {
                1 -> {
                    //Log.d(TAG, "P-frame")
                    fuBuffer.addAll(listOf(0x00, 0x00, 0x00, 0x01).map { it.toByte() })
                    fuBuffer.addAll(payload.toList())
                    fuActive = false

                    var naluData = fuBuffer.toByteArray()
                    //var naluData = fuBuffer.toByteArray().drop(4).toByteArray()
                    //var naluData = fuBuffer.toByteArray()
                    //naluData = writeLengthPrefix(naluData)

                    onNal(naluData, ts.toLong())
                    fuBuffer.clear()
                }

                7 -> {
                    Log.d(TAG, "SPS")
                }

                8 -> {
                    Log.d(TAG, "PPS")
                }

                else -> {
                    Log.d(TAG, "Not FU-A or Single NAL nalType=$nalType")
                }
            }
            return
        }

        Log.d(TAG, "IDR with FU-A")

        val fuHeader = payload[1].toInt() and 0xFF
        val startFlag = (fuHeader and 0x80) != 0
        val endFlag = (fuHeader and 0x40) != 0
        val originalType = fuHeader and 0x1F

        val f = nal0 and 0x80
        val nri = nal0 and 0x60
        val reconstructedNalHeader = f or nri or originalType

        Log.d(TAG,
            "fuHeader=%02X reconstructedNALHeader=%02X"
                .format(fuHeader, reconstructedNalHeader)
        )

        val fragmentData = payload.copyOfRange(2, payload.size)

        if (startFlag) {
            // Start: Annex-B + reconstructed header + fragment
            fuBuffer.clear()
            fuActive = true
            fuBuffer.addAll(listOf(0x00, 0x00, 0x00, 0x01).map { it.toByte() })
            fuBuffer.add(reconstructedNalHeader.toByte())
            fuBuffer.addAll(fragmentData.toList())
        } else if (fuActive) {
            fuBuffer.addAll(fragmentData.toList())
        } else {
            // middle without start
            return
        }

        if (endFlag && fuActive) {
            fuActive = false

            var naluData = fuBuffer.toByteArray()
            //var naluData = fuBuffer.toByteArray()
            //naluData = writeLengthPrefix(naluData)

            onNal(naluData, ts.toLong())
            fuBuffer.clear()
        }
    }
}