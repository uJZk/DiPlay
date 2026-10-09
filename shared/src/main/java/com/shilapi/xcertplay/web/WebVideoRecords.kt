package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.Base64

/**
 * One record of the `GET /video` body: a 24-byte little-endian header, then [prefix] (parameter sets before a
 * key frame) and [payload]. The payload array is shared with the Android decoder and is never modified.
 */
class WebVideoRecord(
    val kind: Int,
    val flags: Int,
    val codec: Int,
    val sequence: Int,
    val epoch: Int,
    val timestampUs: Long,
    val payload: ByteArray,
    val prefix: ByteArray? = null,
    /** System.nanoTime when the record was queued, for the queue's age limit; 0 for records that are not queued. */
    val queuedAtNanos: Long = 0L,
) {
    val payloadLength: Int get() = (prefix?.size ?: 0) + payload.size
    val keyFrame: Boolean get() = flags and WebVideoRecords.FLAG_KEY_FRAME != 0

    fun withFlags(extra: Int) =
        WebVideoRecord(kind, flags or extra, codec, sequence, epoch, timestampUs, payload, prefix, queuedAtNanos)
}

/** Wire format of the browser video stream (protocol v1). */
object WebVideoRecords {
    const val HEADER_SIZE = 24
    const val VERSION = 1
    const val MAX_PAYLOAD = 8 * 1024 * 1024

    const val KIND_CONFIG = 1
    const val KIND_FRAME = 2
    const val KIND_HEARTBEAT = 3
    const val KIND_END = 4

    const val FLAG_KEY_FRAME = 1
    /** Frames were dropped before this one. */
    const val FLAG_DISCONTINUITY = 2
    const val FLAG_PARAMETER_SETS = 4

    const val CODEC_NONE = 0
    const val CODEC_H264 = 1
    const val CODEC_H265 = 2

    /** A decoded header; [payloadLength] includes any parameter-set prefix. */
    data class Header(
        val payloadLength: Int,
        val kind: Int,
        val flags: Int,
        val codec: Int,
        val version: Int,
        val sequence: Int,
        val epoch: Int,
        val timestampUs: Long,
    )

    val HEARTBEAT = WebVideoRecord(KIND_HEARTBEAT, 0, CODEC_NONE, 0, 0, 0L, ByteArray(0))

    fun codecId(codec: VideoCodec) = when (codec) {
        VideoCodec.H264 -> CODEC_H264
        VideoCodec.H265 -> CODEC_H265
    }

    fun config(epoch: Int, codec: VideoCodec, payload: ByteArray) =
        WebVideoRecord(KIND_CONFIG, 0, codecId(codec), 0, epoch, 0L, payload)

    fun end(reason: String) = WebVideoRecord(KIND_END, 0, CODEC_NONE, 0, 0, 0L, reason.toByteArray(Charsets.UTF_8))

    /**
     * The config payload: `{"codec","width","height","fps","format":"annexb","marker","nalLengthBytes","record"}`.
     * [width] and [height] are the negotiated CarPlay canvas; the page maps touches against it.
     */
    fun configPayload(
        codecString: String,
        width: Int,
        height: Int,
        fps: Int,
        codec: VideoCodec,
        nalLengthBytes: Int?,
        record: ByteArray,
    ): ByteArray = WebJson.write(
        linkedMapOf(
            "codec" to codecString,
            "width" to width,
            "height" to height,
            "fps" to fps,
            "format" to "annexb",
            "marker" to if (codec == VideoCodec.H264) "avcC" else "hvcC",
            "nalLengthBytes" to nalLengthBytes,
            "record" to Base64.getEncoder().encodeToString(record),
        ),
    ).toByteArray(Charsets.UTF_8)

    /** Writes [record]'s header into [target] at [offset]. */
    fun writeHeader(record: WebVideoRecord, target: ByteArray, offset: Int = 0) {
        writeU32Le(target, offset, record.payloadLength)
        target[offset + 4] = record.kind.toByte()
        target[offset + 5] = record.flags.toByte()
        target[offset + 6] = record.codec.toByte()
        target[offset + 7] = VERSION.toByte()
        writeU32Le(target, offset + 8, record.sequence)
        writeU32Le(target, offset + 12, record.epoch)
        for (index in 0 until 8) target[offset + 16 + index] = (record.timestampUs ushr (8 * index)).toByte()
    }

    /** Writes the whole record (header, prefix, payload) into [target] at [offset] and returns its size. */
    fun write(record: WebVideoRecord, target: ByteArray, offset: Int = 0): Int {
        writeHeader(record, target, offset)
        var cursor = offset + HEADER_SIZE
        record.prefix?.let { it.copyInto(target, cursor); cursor += it.size }
        record.payload.copyInto(target, cursor)
        return HEADER_SIZE + record.payloadLength
    }

    fun encode(record: WebVideoRecord): ByteArray =
        ByteArray(HEADER_SIZE + record.payloadLength).also { write(record, it) }

    /** Reads a header; throws [IllegalArgumentException] for an unknown version or a payload over [MAX_PAYLOAD]. */
    fun readHeader(source: ByteArray, offset: Int = 0): Header {
        require(source.size - offset >= HEADER_SIZE) { "short header" }
        val payloadLength = readU32Le(source, offset)
        val version = source[offset + 7].toInt() and 0xff
        require(version == VERSION) { "unknown version $version" }
        require(payloadLength in 0..MAX_PAYLOAD) { "payload too large" }
        var timestamp = 0L
        for (index in 7 downTo 0) timestamp = (timestamp shl 8) or (source[offset + 16 + index].toLong() and 0xff)
        return Header(
            payloadLength = payloadLength,
            kind = source[offset + 4].toInt() and 0xff,
            flags = source[offset + 5].toInt() and 0xff,
            codec = source[offset + 6].toInt() and 0xff,
            version = version,
            sequence = readU32Le(source, offset + 8),
            epoch = readU32Le(source, offset + 12),
            timestampUs = timestamp,
        )
    }

    private fun writeU32Le(target: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) target[offset + index] = (value ushr (8 * index)).toByte()
    }

    private fun readU32Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)
}
