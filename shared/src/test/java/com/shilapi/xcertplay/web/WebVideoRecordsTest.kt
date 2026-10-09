package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.Executor

class WebVideoRecordsTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun headerFieldsAreLittleEndianAndRoundTrip() {
        val record = WebVideoRecord(
            WebVideoRecords.KIND_FRAME,
            WebVideoRecords.FLAG_KEY_FRAME or WebVideoRecords.FLAG_DISCONTINUITY or WebVideoRecords.FLAG_PARAMETER_SETS,
            WebVideoRecords.CODEC_H265,
            sequence = 0x01020304,
            epoch = -2,
            timestampUs = 0x0102030405060708,
            payload = bytes(9, 9, 9),
            prefix = bytes(7),
        )
        val encoded = WebVideoRecords.encode(record)
        assertArrayEquals(
            bytes(
                4, 0, 0, 0, 2, 7, 2, 1,
                4, 3, 2, 1, 0xfe, 0xff, 0xff, 0xff,
                8, 7, 6, 5, 4, 3, 2, 1,
                7, 9, 9, 9,
            ),
            encoded,
        )
        assertEquals(
            WebVideoRecords.Header(4, 2, 7, 2, 1, 0x01020304, -2, 0x0102030405060708),
            WebVideoRecords.readHeader(encoded),
        )
        val negative = WebVideoRecord(WebVideoRecords.KIND_FRAME, 0, 1, 0, 1, -16_666L, ByteArray(0))
        assertEquals(-16_666L, WebVideoRecords.readHeader(WebVideoRecords.encode(negative)).timestampUs)
    }

    @Test fun writeFillsALargerBufferAtAnOffset() {
        val record = WebVideoRecords.end("stopped")
        val buffer = ByteArray(64) { 0x55 }
        assertEquals(31, WebVideoRecords.write(record, buffer, 8))
        assertEquals(0x55.toByte(), buffer[7])
        assertEquals(WebVideoRecords.KIND_END, WebVideoRecords.readHeader(buffer, 8).kind)
        assertEquals("stopped", String(buffer, 32, 7, Charsets.UTF_8))
        assertEquals(0x55.toByte(), buffer[39])
    }

    @Test fun readerRejectsUnknownVersionsShortHeadersAndOversizedPayloads() {
        val header = WebVideoRecords.encode(WebVideoRecords.HEARTBEAT)
        assertThrows(IllegalArgumentException::class.java) { WebVideoRecords.readHeader(header.copyOf(23)) }
        assertThrows(IllegalArgumentException::class.java) {
            WebVideoRecords.readHeader(header.copyOf().apply { this[7] = 2 })
        }
        val limit = header.copyOf().apply { this[0] = 0; this[1] = 0; this[2] = 0x80.toByte(); this[3] = 0 }
        assertEquals(WebVideoRecords.MAX_PAYLOAD, WebVideoRecords.readHeader(limit).payloadLength)
        assertThrows(IllegalArgumentException::class.java) {
            WebVideoRecords.readHeader(limit.copyOf().apply { this[0] = 1 })
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebVideoRecords.readHeader(header.copyOf().apply { this[3] = 0xff.toByte() })
        }
    }

    @Test fun configPayloadIsTheContractJson() {
        val payload = WebVideoRecords.configPayload("avc1.640028", 1182, 920, 60, VideoCodec.H264, 4, bytes(1, 2, 3))
        assertEquals(
            """{"codec":"avc1.640028","width":1182,"height":920,"fps":60,"format":"annexb","marker":"avcC",""" +
                """"nalLengthBytes":4,"record":"AQID"}""",
            String(payload, Charsets.UTF_8),
        )
        val hevc = String(WebVideoRecords.configPayload("hvc1.1.6.L120.B0", 640, 480, 30, VideoCodec.H265, 4, bytes(1)))
        assertTrue(hevc.contains(""""marker":"hvcC""""))
        val config = WebVideoRecords.config(3, VideoCodec.H265, payload)
        assertEquals(WebVideoRecords.Header(payload.size, 1, 0, 2, 1, 0, 3, 0), WebVideoRecords.readHeader(WebVideoRecords.encode(config)))
    }

    @Test fun heartbeatAndEndCarryNoCodec() {
        val heartbeat = WebVideoRecords.readHeader(WebVideoRecords.encode(WebVideoRecords.HEARTBEAT))
        assertEquals(WebVideoRecords.Header(0, 3, 0, 0, 1, 0, 0, 0), heartbeat)
        val end = WebVideoRecords.encode(WebVideoRecords.end("replaced"))
        assertEquals(WebVideoRecords.Header(8, 4, 0, 0, 1, 0, 0, 0), WebVideoRecords.readHeader(end))
        assertFalse(WebVideoRecords.end("x").keyFrame)
    }

    /**
     * The page's JavaScript tests parse the same files. The fixture is produced by the real pipeline (hub, subscription,
     * record encoder); the actual output is also written to `shared/build/test-fixtures/web/` for review and regeneration.
     */
    @Test fun hubOutputMatchesTheCommittedFixture() {
        val produced = produceFixtureRecords()
        val binary = produced.flatMap { WebVideoRecords.encode(it).asList() }.toByteArray()
        val json = describe(binary)

        val out = File("build/test-fixtures/web").apply { mkdirs() }
        File(out, "records-v1.bin").writeBytes(binary)
        File(out, "records-v1.json").writeText(json)
        val committedBinary = javaClass.getResourceAsStream("/web/records-v1.bin")?.readBytes()
        val committedJson = javaClass.getResourceAsStream("/web/records-v1.json")?.readBytes()?.toString(Charsets.UTF_8)
        assertArrayEquals("fixture differs; see ${out.absolutePath}", committedBinary, binary)
        assertEquals("fixture description differs; see ${out.absolutePath}", committedJson, json)
    }

    private fun produceFixtureRecords(): List<WebVideoRecord> {
        var now = 5_000_000_000L
        val hub = WebVideoHub(Executor { it.run() }, { now })
        val owner = Any()
        hub.beginStream(owner, 1182, 920, 60, {}, {})
        val subscription = hub.subscribe()
        hub.configure(owner, VideoCodec.H264, FIXTURE_AVCC)
        val senderNanos = 3_000_000_000L
        hub.frame(owner, FIXTURE_KEY_FRAME.copyOf(), senderNanos, now)
        now += 16_666_667
        hub.frame(owner, FIXTURE_DELTA.copyOf(), senderNanos + 16_666_667, now)
        val records = List(3) { subscription.take(0) ?: error("missing record $it") }
        subscription.end("stopped")
        return records + WebVideoRecords.HEARTBEAT + (subscription.take(0) ?: error("missing end"))
    }

    private fun describe(binary: ByteArray): String {
        val lines = mutableListOf<String>()
        var offset = 0
        while (offset < binary.size) {
            val header = WebVideoRecords.readHeader(binary, offset)
            val payload = binary.copyOfRange(offset + WebVideoRecords.HEADER_SIZE, offset + WebVideoRecords.HEADER_SIZE + header.payloadLength)
            val name = when (header.kind) {
                WebVideoRecords.KIND_CONFIG -> "config"
                WebVideoRecords.KIND_FRAME -> if (header.flags and WebVideoRecords.FLAG_KEY_FRAME != 0) "key frame" else "delta frame"
                WebVideoRecords.KIND_HEARTBEAT -> "heartbeat"
                else -> "end"
            }
            lines += WebJson.write(
                linkedMapOf(
                    "name" to name,
                    "offset" to offset,
                    "payloadLength" to header.payloadLength,
                    "kind" to header.kind,
                    "flags" to header.flags,
                    "codec" to header.codec,
                    "version" to header.version,
                    "sequence" to header.sequence,
                    "epoch" to header.epoch,
                    "timestampUs" to header.timestampUs,
                    "payload" to Base64.getEncoder().encodeToString(payload),
                ),
            )
            offset += WebVideoRecords.HEADER_SIZE + header.payloadLength
        }
        return "{\n" +
            "  \"description\": \"TiPlay browser video records, protocol v1: records-v1.bin is these records back to back. " +
            "payload is the base64 record payload as sent (parameter sets included).\",\n" +
            "  \"headerSize\": ${WebVideoRecords.HEADER_SIZE},\n" +
            "  \"records\": [\n    " + lines.joinToString(",\n    ") + "\n  ]\n}\n"
    }

    private companion object {
        /** avcC, High@4.0, 4-byte NAL lengths, a 4-byte SPS and a 2-byte PPS. */
        val FIXTURE_AVCC = byteArrayOf(1, 0x64, 0x00, 0x28, -1, -31, 0, 4, 0x67, 0x64, 0x00, 0x28, 1, 0, 2, 0x68, -18)
        val FIXTURE_KEY_FRAME = byteArrayOf(0, 0, 0, 1, 0x65, -120, -124, 0x21, 0x10)
        val FIXTURE_DELTA = byteArrayOf(0, 0, 0, 1, 0x41, -102, 0x02, 0x10)
    }
}
