package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenCodecTest {
    @Test
    fun validLengthPrefixesBecomeAnnexBInPlace() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        val converted = ScreenCodec.lengthPrefixedToAnnexB(payload)

        assertSame(payload, converted)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            converted,
        )
    }

    @Test
    fun malformedLengthsLeavePayloadUntouched() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val original = payload.copyOf()

        assertSame(payload, ScreenCodec.lengthPrefixedToAnnexB(payload))
        assertArrayEquals(original, payload)
    }

    @Test fun readsTheFrameTimeFromTheScreenHeader() {
        val header = ByteArray(128)
        // NTP 32.32 little-endian at byte 8: 3 s + 0x40000000/2^32 s = 3.25 s.
        val raw = (3L shl 32) or 0x4000_0000L
        for (i in 0 until 8) header[8 + i] = (raw ushr (8 * i)).toByte()
        assertEquals(3_250_000_000L, ScreenCodec.senderNanos(header))
        // One 1/60 s step, as the iPhone stamps consecutive main-screen frames.
        val next = raw + 0x0444_4444L
        for (i in 0 until 8) header[8 + i] = (next ushr (8 * i)).toByte()
        assertEquals(16_666_666L, ScreenCodec.senderNanos(header) - 3_250_000_000L)
        assertEquals(0L, ScreenCodec.senderNanos(ByteArray(12)))
    }

    private val avcC = byteArrayOf(1, 0x64, 0x00, 0x28, -1, -31, 0, 4, 0x67, 0x64, 0x00, 0x28, 1, 0, 2, 0x68, -18)
    private val hvcC = ByteArray(23).apply { this[0] = 1; this[1] = 0x21; this[12] = 120; this[21] = 0x0f }

    private fun withMarker(prefix: Int, marker: String, record: ByteArray) =
        ByteArray(prefix) + marker.toByteArray(Charsets.US_ASCII) + record

    @Test fun describeConfigReportsTheMarkerAndWhereItWas() {
        val avc = ScreenCodec.describeConfig(withMarker(4, "avcC", avcC))
        assertEquals(VideoCodec.H264, avc.codec)
        assertEquals("avcC", avc.marker)
        assertEquals(4, avc.markerOffset)
        assertArrayEquals(avcC, avc.record)

        val hevc = ScreenCodec.describeConfig(withMarker(12, "hvcC", hvcC))
        assertEquals(VideoCodec.H265, hevc.codec)
        assertEquals("hvcC", hevc.marker)
        assertEquals(12, hevc.markerOffset)
        assertArrayEquals(hvcC, hevc.record)
    }

    @Test fun describeConfigWithoutAMarkerGuessesLikeDetectConfig() {
        val guessed = ScreenCodec.describeConfig(avcC)
        assertEquals(VideoCodec.H264, guessed.codec)
        assertEquals("none", guessed.marker)
        assertEquals(-1, guessed.markerOffset)
        assertSame(avcC, guessed.record)
        assertEquals(VideoCodec.H265, ScreenCodec.describeConfig(hvcC).codec)

        for (payload in listOf(withMarker(4, "avcC", avcC), withMarker(6, "hvcC", hvcC), avcC, hvcC, ByteArray(3))) {
            val (codec, record) = ScreenCodec.detectConfig(payload)
            val described = ScreenCodec.describeConfig(payload)
            assertEquals(described.codec, codec)
            assertArrayEquals(described.record, record)
        }
    }

    @Test fun configDiagnosticNamesTheFormatWithoutPayloadBytes() {
        assertEquals(
            "Video config marker=avcC at=4 codec=H264 recordBytes=17 nalLengthBytes=4 profile=100 level=40",
            ScreenCodec.configDiagnostic(ScreenCodec.describeConfig(withMarker(4, "avcC", avcC))),
        )
        assertEquals(
            "Video config marker=hvcC at=8 codec=H265 recordBytes=23 nalLengthBytes=4 profile=1 tier=1 level=120",
            ScreenCodec.configDiagnostic(ScreenCodec.describeConfig(withMarker(8, "hvcC", hvcC))),
        )
        assertEquals(
            "Video config marker=none at=-1 codec=H265 recordBytes=3 nalLengthBytes=unknown profile=unknown level=unknown",
            ScreenCodec.configDiagnostic(ScreenCodec.describeConfig(ByteArray(3))),
        )
        assertEquals(
            "Video config marker=none at=-1 codec=H264 recordBytes=17 nalLengthBytes=2 profile=100 level=40",
            ScreenCodec.configDiagnostic(ScreenCodec.describeConfig(avcC.copyOf().apply { this[4] = 0xfd.toByte() })),
        )
        // The session log drops lines with these words and masks long hex runs.
        val big = withMarker(4, "avcC", avcC + ByteArray(400) { 0x5a })
        val line = ScreenCodec.configDiagnostic(ScreenCodec.describeConfig(big))
        for (word in listOf("body=", "payload=", "hex=")) assertFalse(line, line.contains(word))
        assertFalse(line, Regex("[0-9a-fA-F]{16,}").containsMatchIn(line))
    }

    @Test fun nalLengthBytesReadsLengthSizeMinusOne() {
        assertEquals(4, ScreenCodec.nalLengthBytes(VideoCodec.H264, avcC))
        assertEquals(1, ScreenCodec.nalLengthBytes(VideoCodec.H264, avcC.copyOf().apply { this[4] = 0xfc.toByte() }))
        assertEquals(4, ScreenCodec.nalLengthBytes(VideoCodec.H265, hvcC))
        assertEquals(null, ScreenCodec.nalLengthBytes(VideoCodec.H265, avcC))
        assertEquals(null, ScreenCodec.nalLengthBytes(VideoCodec.H264, avcC.copyOf().apply { this[0] = 0 }))
    }
}
