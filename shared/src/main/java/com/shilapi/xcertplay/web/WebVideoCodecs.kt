// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
// common/src/main/java/com/shilapi/xcertplay/web/WebVideoSource.kt at commit c1bd077. Modified for TiPlay, 2026-10.
package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.ScreenCodec
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MediaCodecSupport

/** What a browser needs from an avcC/hvcC record: the WebCodecs codec string and Annex-B parameter sets. */
object WebVideoCodecs {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * The WebCodecs codec string, or null when the record cannot configure a decoder.
     *
     * H.264: `avc1.` + avcC bytes 1..3 (profile, constraint flags, level) in hex, e.g. `avc1.640028`.
     * HEVC (ISO/IEC 14496-15 Annex E): `hvc1.<profile>.<compatibility>.<L|H><level>[.<constraint bytes>]`, where the
     * compatibility flags are reversed (flag 31 becomes the most significant bit) and trailing zero constraint bytes
     * are dropped; Main at level 4.0 is `hvc1.1.6.L120.B0`.
     */
    fun codecString(codec: VideoCodec, record: ByteArray): String? = when (codec) {
        VideoCodec.H264 -> if (avcParameterSets(record).isEmpty()) null else
            "avc1." + record.copyOfRange(1, 4).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        VideoCodec.H265 -> if (hevcParameterSets(record).isEmpty()) null else hevcCodecString(record)
    }

    /** Parameter sets to prepend to every key frame (H.264 SPS+PPS, HEVC VPS+SPS+PPS) with start codes; empty when invalid. */
    fun parameterSets(codec: VideoCodec, record: ByteArray): ByteArray = when (codec) {
        VideoCodec.H264 -> avcParameterSets(record)
        VideoCodec.H265 -> hevcParameterSets(record)
    }

    /** The record's NAL length size in bytes (lengthSizeMinusOne + 1), or null when the record is too short. */
    fun nalLengthSize(codec: VideoCodec, record: ByteArray): Int? = ScreenCodec.nalLengthBytes(codec, record)

    /**
     * True when the Annex-B access unit is an H.264 IDR or HEVC IRAP picture, the same rule as
     * [MediaCodecSupport.isRandomAccess]. Every slice of a picture has the same NAL type, so the scan stops
     * at the first slice instead of reading the whole frame on the screen thread.
     */
    fun isKeyFrame(codec: VideoCodec, annexB: ByteArray): Boolean {
        var cursor = 0
        while (cursor + 3 < annexB.size) {
            if (annexB[cursor] != 0.toByte() || annexB[cursor + 1] != 0.toByte() || annexB[cursor + 2] != 1.toByte()) {
                cursor++
                continue
            }
            val header = annexB[cursor + 3].toInt() and 0xff
            when (codec) {
                VideoCodec.H264 -> (header and 0x1f).let { if (it in 1..5) return it == 5 }
                VideoCodec.H265 -> ((header shr 1) and 0x3f).let { if (it <= 31) return it in 16..21 }
            }
            cursor += 4
        }
        return false
    }

    private fun avcParameterSets(record: ByteArray): ByteArray {
        if (record.size < 7 || record[0].toInt() != 1) return ByteArray(0)
        val (sps, pps) = MediaCodecSupport.avcParameterSets(record)
        if (sps.size < 4 || pps.isEmpty()) return ByteArray(0)
        return START_CODE + sps + START_CODE + pps
    }

    /** Annex-B VPS/SPS/PPS, empty unless all three are present with NAL headers that match their arrays. */
    private fun hevcParameterSets(record: ByteArray): ByteArray {
        if (record.size < 23 || record[0].toInt() != 1) return ByteArray(0)
        val profile = record[1].toInt() and 0xff
        // Profile space must be 0 for HEVC version 1+ decoders; do not mislabel vendor profiles.
        if (profile ushr 6 != 0 || profile and 0x1f == 0 || record[12].toInt() == 0) return ByteArray(0)
        var cursor = 23
        val found = mutableSetOf<Int>()
        repeat(record[22].toInt() and 0xff) {
            if (cursor + 3 > record.size) return ByteArray(0)
            val kind = record[cursor++].toInt() and 0x3f
            val count = readU16Be(record, cursor)
            cursor += 2
            repeat(count) {
                if (cursor + 2 > record.size) return ByteArray(0)
                val size = readU16Be(record, cursor)
                cursor += 2
                if (size < 2 || size > record.size - cursor || ((record[cursor].toInt() and 0xff) ushr 1) and 0x3f != kind) {
                    return ByteArray(0)
                }
                found += kind
                cursor += size
            }
        }
        if (!found.containsAll(listOf(HEVC_VPS, HEVC_SPS, HEVC_PPS))) return ByteArray(0)
        return MediaCodecSupport.hevcCodecSpecificData(record)
    }

    private fun hevcCodecString(record: ByteArray): String {
        val profile = record[1].toInt() and 0xff
        val compatibility = Integer.reverse(
            ((record[2].toInt() and 0xff) shl 24) or ((record[3].toInt() and 0xff) shl 16) or
                ((record[4].toInt() and 0xff) shl 8) or (record[5].toInt() and 0xff),
        )
        val tier = if ((profile shr 5) and 1 == 1) "H" else "L"
        val constraints = (6..11).map { record[it].toInt() and 0xff }.dropLastWhile { it == 0 }
        return buildString {
            append("hvc1.").append(profile and 0x1f)
            append('.').append(Integer.toHexString(compatibility).uppercase())
            append('.').append(tier).append(record[12].toInt() and 0xff)
            constraints.forEach { append('.').append("%X".format(it)) }
        }
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private const val HEVC_VPS = 32
    private const val HEVC_SPS = 33
    private const val HEVC_PPS = 34
}
