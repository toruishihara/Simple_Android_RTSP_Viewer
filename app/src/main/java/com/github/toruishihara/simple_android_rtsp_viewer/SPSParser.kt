package com.github.toruishihara.simple_android_rtsp_viewer

class SPSParser {
    fun parseSps(sps: ByteArray): VideoSize {
        require(sps.isNotEmpty()) { "SPS is empty" }
        require((sps[0].toInt() and 0x1F) == 7) { "Not an SPS NAL" }

        // Skip NAL header, then remove emulation prevention bytes
        val rbsp = removeEmulationPreventionBytes(sps.copyOfRange(1, sps.size))
        val br = BitReader(rbsp)

        val profileIdc = br.readBits(8)
        br.readBits(8) // constraint_set flags + reserved_zero_2bits
        br.readBits(8) // level_idc
        br.readUE()    // seq_parameter_set_id

        var chromaFormatIdc = 1
        if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 ||
            profileIdc == 244 || profileIdc == 44 || profileIdc == 83 ||
            profileIdc == 86 || profileIdc == 118 || profileIdc == 128 ||
            profileIdc == 138 || profileIdc == 139 || profileIdc == 134
        ) {
            chromaFormatIdc = br.readUE()
            if (chromaFormatIdc == 3) {
                br.readBit() // separate_colour_plane_flag
            }
            br.readUE() // bit_depth_luma_minus8
            br.readUE() // bit_depth_chroma_minus8
            br.readBit() // qpprime_y_zero_transform_bypass_flag
            val seqScalingMatrixPresentFlag = br.readBit()
            if (seqScalingMatrixPresentFlag == 1) {
                val scalingCount = if (chromaFormatIdc != 3) 8 else 12
                repeat(scalingCount) {
                    val seqScalingListPresentFlag = br.readBit()
                    if (seqScalingListPresentFlag == 1) {
                        // Skip scaling list
                        var lastScale = 8
                        var nextScale = 8
                        val sizeOfScalingList = if (it < 6) 16 else 64
                        repeat(sizeOfScalingList) {
                            if (nextScale != 0) {
                                val deltaScale = br.readSE()
                                nextScale = (lastScale + deltaScale + 256) % 256
                            }
                            lastScale = if (nextScale == 0) lastScale else nextScale
                        }
                    }
                }
            }
        }

        br.readUE() // log2_max_frame_num_minus4

        val picOrderCntType = br.readUE()
        if (picOrderCntType == 0) {
            br.readUE() // log2_max_pic_order_cnt_lsb_minus4
        } else if (picOrderCntType == 1) {
            br.readBit() // delta_pic_order_always_zero_flag
            br.readSE()  // offset_for_non_ref_pic
            br.readSE()  // offset_for_top_to_bottom_field
            val numRefFramesInPicOrderCntCycle = br.readUE()
            repeat(numRefFramesInPicOrderCntCycle) {
                br.readSE() // offset_for_ref_frame[i]
            }
        }

        br.readUE()  // max_num_ref_frames
        br.readBit() // gaps_in_frame_num_value_allowed_flag

        val picWidthInMbsMinus1 = br.readUE()
        val picHeightInMapUnitsMinus1 = br.readUE()

        val frameMbsOnlyFlag = br.readBit()
        if (frameMbsOnlyFlag == 0) {
            br.readBit() // mb_adaptive_frame_field_flag
        }

        br.readBit() // direct_8x8_inference_flag

        val frameCroppingFlag = br.readBit()

        var cropLeft = 0
        var cropRight = 0
        var cropTop = 0
        var cropBottom = 0

        if (frameCroppingFlag == 1) {
            cropLeft = br.readUE()
            cropRight = br.readUE()
            cropTop = br.readUE()
            cropBottom = br.readUE()
        }

        val subWidthC = when (chromaFormatIdc) {
            0, 3 -> 1
            1, 2 -> 2
            else -> 1
        }

        val subHeightC = when (chromaFormatIdc) {
            0 -> 1
            1 -> 2
            2, 3 -> 1
            else -> 1
        }

        val cropUnitX = if (chromaFormatIdc == 0) 1 else subWidthC
        val cropUnitY = if (chromaFormatIdc == 0) {
            2 - frameMbsOnlyFlag
        } else {
            subHeightC * (2 - frameMbsOnlyFlag)
        }

        val width = (picWidthInMbsMinus1 + 1) * 16 - (cropLeft + cropRight) * cropUnitX
        val height = (picHeightInMapUnitsMinus1 + 1) * 16 * (2 - frameMbsOnlyFlag) -
                (cropTop + cropBottom) * cropUnitY

        return VideoSize(width, height)
    }

}

data class VideoSize(
    val width: Int,
    val height: Int
)

class BitReader(private val data: ByteArray) {
    private var bytePos = 0
    private var bitPos = 0

    fun readBit(): Int {
        if (bytePos >= data.size) error("Read past end of buffer")
        val value = (data[bytePos].toInt() shr (7 - bitPos)) and 1
        bitPos++
        if (bitPos == 8) {
            bitPos = 0
            bytePos++
        }
        return value
    }

    fun readBits(n: Int): Int {
        var v = 0
        repeat(n) {
            v = (v shl 1) or readBit()
        }
        return v
    }

    fun readUE(): Int {
        var zeros = 0
        while (readBit() == 0) {
            zeros++
        }
        var value = 1
        repeat(zeros) {
            value = (value shl 1) or readBit()
        }
        return value - 1
    }

    fun readSE(): Int {
        val codeNum = readUE()
        return if (codeNum % 2 == 0) {
            -(codeNum / 2)
        } else {
            (codeNum + 1) / 2
        }
    }
}

fun removeEmulationPreventionBytes(data: ByteArray): ByteArray {
    val out = ArrayList<Byte>(data.size)
    var i = 0
    while (i < data.size) {
        if (i + 2 < data.size &&
            data[i] == 0.toByte() &&
            data[i + 1] == 0.toByte() &&
            data[i + 2] == 3.toByte()
        ) {
            out.add(0)
            out.add(0)
            i += 3
        } else {
            out.add(data[i])
            i++
        }
    }
    return out.toByteArray()
}
