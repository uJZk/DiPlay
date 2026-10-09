// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
// common/src/test/java/com/shilapi/xcertplay/web/WebVideoSourceTest.kt at commit c1bd077. Modified for TeslaPlay, 2026-10.
package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.MediaCodecSupport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebVideoCodecsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** avcC, High profile (100), no constraint flags, level 4.0 (40), 4-byte NAL lengths, one SPS and one PPS. */
    private val avcc = bytes(1, 0x64, 0x00, 0x28, 0xff, 0xe1, 0, 4, 0x67, 0x64, 0x00, 0x28, 1, 0, 2, 0x68, 0xee)

    /**
     * hvcC, Main profile, Main tier, level 4.0 (general_level_idc 120 = 30 × 4.0), 4-byte NAL lengths.
     * general_profile_compatibility_flags are stored flag[0] first, so bytes 60 00 00 00 set flags 1 (Main) and 2 (Main 10).
     * Annex E writes them reversed (flag 31 as the most significant bit): 0b110 = 6. The constraint bytes start with
     * progressive_source, non_packed and frame_only = 0xB0. ISO/IEC 14496-15 Annex E gives `hvc1.1.6.L93.B0` for
     * Main at level 3.1 (93); this record is the same at level 4.0.
     */
    private fun hvcc() = bytes(
        1, 0x01, 0x60, 0, 0, 0, 0xb0, 0, 0, 0, 0, 0, 120, 0xf0, 0, 0xfc, 0xfd, 0xf8, 0xf8, 0, 0, 0x0f, 3,
        0xa0, 0, 1, 0, 2, 0x40, 0x01,
        0xa1, 0, 1, 0, 2, 0x42, 0x01,
        0xa2, 0, 1, 0, 2, 0x44, 0x01,
    )

    @Test fun h264CodecStringIsProfileConstraintsAndLevelInHex() {
        assertEquals("avc1.640028", WebVideoCodecs.codecString(VideoCodec.H264, avcc))
        val baseline = avcc.copyOf().apply { this[1] = 0x42; this[2] = 0xe0.toByte(); this[3] = 0x1f }
        assertEquals("avc1.42e01f", WebVideoCodecs.codecString(VideoCodec.H264, baseline))
    }

    @Test fun hevcCodecStringReversesTheCompatibilityFlagsAndDropsTrailingZeroConstraints() {
        assertEquals("hvc1.1.6.L120.B0", WebVideoCodecs.codecString(VideoCodec.H265, hvcc()))
        // Main 10, High tier, level 5.1: compatibility flag 2 only (0x20000000 reversed = 4).
        val main10 = hvcc().apply { this[1] = 0x22; this[2] = 0x20; this[12] = 153.toByte() }
        assertEquals("hvc1.2.4.H153.B0", WebVideoCodecs.codecString(VideoCodec.H265, main10))
        assertEquals("hvc1.1.6.L120", WebVideoCodecs.codecString(VideoCodec.H265, hvcc().apply { this[6] = 0 }))
        assertEquals("hvc1.1.6.L120.B0.0.0.0.0.1", WebVideoCodecs.codecString(VideoCodec.H265, hvcc().apply { this[11] = 1 }))
    }

    @Test fun parameterSetsAreAnnexBForEveryKeyFrame() {
        assertArrayEquals(
            bytes(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0, 0, 0, 1, 0x68, 0xee),
            WebVideoCodecs.parameterSets(VideoCodec.H264, avcc),
        )
        assertArrayEquals(
            bytes(0, 0, 0, 1, 0x40, 0x01, 0, 0, 0, 1, 0x42, 0x01, 0, 0, 0, 1, 0x44, 0x01),
            WebVideoCodecs.parameterSets(VideoCodec.H265, hvcc()),
        )
    }

    @Test fun nalLengthSizeComesFromLengthSizeMinusOne() {
        assertEquals(4, WebVideoCodecs.nalLengthSize(VideoCodec.H264, avcc))
        assertEquals(1, WebVideoCodecs.nalLengthSize(VideoCodec.H264, avcc.copyOf().apply { this[4] = 0xfc.toByte() }))
        assertEquals(4, WebVideoCodecs.nalLengthSize(VideoCodec.H265, hvcc()))
        assertEquals(2, WebVideoCodecs.nalLengthSize(VideoCodec.H265, hvcc().apply { this[21] = 0x0d }))
        assertNull(WebVideoCodecs.nalLengthSize(VideoCodec.H264, bytes(1, 0x64)))
        assertNull(WebVideoCodecs.nalLengthSize(VideoCodec.H265, avcc))
    }

    @Test fun invalidH264RecordsHaveNoCodecStringOrParameterSets() {
        for (record in listOf(
            ByteArray(0), bytes(1), avcc.copyOf(6), avcc.copyOf().apply { this[0] = 0 },
            avcc.copyOf(13), // no PPS
            avcc.copyOf().apply { this[7] = 2 }, // SPS shorter than its profile bytes
        )) {
            assertNull(WebVideoCodecs.codecString(VideoCodec.H264, record))
            assertEquals(0, WebVideoCodecs.parameterSets(VideoCodec.H264, record).size)
        }
    }

    @Test fun hevcNeedsStandardProfileLevelAndAllThreeMatchingParameterSets() {
        for (record in listOf(
            hvcc().copyOf(22), hvcc().copyOf(30), hvcc().apply { this[0] = 0 },
            hvcc().apply { this[22] = 2 }, // PPS missing
            hvcc().apply { this[1] = 0x41 }, // profile space 1
            hvcc().apply { this[1] = 0 }, // profile_idc 0
            hvcc().apply { this[12] = 0 }, // level 0
            hvcc().apply { this[35] = 0x40 }, // SPS array holds a VPS header
            hvcc().apply { this[27] = 1 }, // one-byte NAL unit
        )) {
            assertNull(WebVideoCodecs.codecString(VideoCodec.H265, record))
            assertEquals(0, WebVideoCodecs.parameterSets(VideoCodec.H265, record).size)
        }
        // An avcC is not an hvcC.
        assertNull(WebVideoCodecs.codecString(VideoCodec.H265, avcc))
    }

    @Test fun keyFramesAreIdrOrIrapAndTheScanStopsAtTheFirstSlice() {
        val idr = bytes(0, 0, 0, 1, 0x09, 0xf0, 0, 0, 0, 1, 0x67, 0x64, 0, 0, 0, 1, 0x68, 0xee, 0, 0, 0, 1, 0x65, 0x88)
        val delta = bytes(0, 0, 0, 1, 0x06, 0x05, 0x01, 0, 0, 1, 0x41, 0x9a)
        // A non-IDR slice first: a later IDR-typed byte sequence is not looked at.
        val sliceFirst = bytes(0, 0, 0, 1, 0x41, 0x9a, 0, 0, 0, 1, 0x65, 0x88)
        assertTrue(WebVideoCodecs.isKeyFrame(VideoCodec.H264, idr))
        assertFalse(WebVideoCodecs.isKeyFrame(VideoCodec.H264, delta))
        assertFalse(WebVideoCodecs.isKeyFrame(VideoCodec.H264, sliceFirst))
        assertFalse(WebVideoCodecs.isKeyFrame(VideoCodec.H264, ByteArray(0)))
        assertFalse(WebVideoCodecs.isKeyFrame(VideoCodec.H264, bytes(0x65, 0x88, 0x84)))

        val hevcIdr = bytes(0, 0, 0, 1, 0x40, 0x01, 0, 0, 0, 1, 0x26, 0x01, 0xaf)
        val hevcCra = bytes(0, 0, 0, 1, 0x2a, 0x01, 0xaf)
        val hevcTrail = bytes(0, 0, 0, 1, 0x4e, 0x01, 0x05, 0, 0, 0, 1, 0x02, 0x01, 0xd0)
        assertTrue(WebVideoCodecs.isKeyFrame(VideoCodec.H265, hevcIdr))
        assertTrue(WebVideoCodecs.isKeyFrame(VideoCodec.H265, hevcCra))
        assertFalse(WebVideoCodecs.isKeyFrame(VideoCodec.H265, hevcTrail))

        // Same answers as the Android decoder's rule for these access units.
        for ((codec, unit) in listOf(VideoCodec.H264 to idr, VideoCodec.H264 to delta, VideoCodec.H265 to hevcIdr,
            VideoCodec.H265 to hevcCra, VideoCodec.H265 to hevcTrail)) {
            assertEquals(MediaCodecSupport.isRandomAccess(unit, codec), WebVideoCodecs.isKeyFrame(codec, unit))
        }
    }
}
