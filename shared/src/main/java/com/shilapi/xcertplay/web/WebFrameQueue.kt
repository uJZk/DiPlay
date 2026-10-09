// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
// common/src/main/java/com/shilapi/xcertplay/web/RtcFrameQueue.kt at commit c1bd077. Modified for TeslaPlay, 2026-10.
package com.shilapi.xcertplay.web

/**
 * The records waiting for one `/video` writer. Caller holds the hub lock. Predictive frames are never
 * retained across a gap: when the oldest frame is too old, or the queue is full, every queued frame is
 * dropped and frames are skipped until the next key frame, which then carries [WebVideoRecords.FLAG_DISCONTINUITY].
 * Config records are never dropped and keep their place between frames.
 */
internal class WebFrameQueue(
    // TCP retransmits instead of losing packets, so allow more than WheelPlay's 50 ms WebRTC budget.
    private val maxAgeNanos: Long = 150_000_000,
    private val maxBytes: Int = 4 * 1024 * 1024,
    private val maxFrames: Int = 10,
) {
    private val records = ArrayDeque<WebVideoRecord>()
    private var started = false
    private var gap = false
    var size = 0; private set
    var bytes = 0; private set
    var needsKeyFrame = true; private set
    /** When the queue began waiting for a key frame, or null while it is not waiting. */
    var waitingSinceNanos: Long? = null; private set
    var expired = 0L; private set
    var overflow = 0L; private set
    var skipped = 0L; private set

    /** Queues a frame; false when it was skipped while waiting for a key frame or is larger than the queue. */
    fun offer(frame: WebVideoRecord, now: Long): Boolean {
        expire(now)
        val cost = frame.payloadLength
        if (cost > maxBytes) { overflow++; invalidate(now); return false }
        if (size >= maxFrames || cost > maxBytes - bytes) {
            overflow += size
            invalidate(now)
        }
        if (needsKeyFrame && !frame.keyFrame) {
            if (waitingSinceNanos == null) waitingSinceNanos = now
            skipped++
            // Deltas before the first key frame of a subscription are not a gap in what the page has seen.
            if (started) gap = true
            return false
        }
        records.addLast(if (gap) frame.withFlags(WebVideoRecords.FLAG_DISCONTINUITY) else frame)
        size++
        bytes += cost
        needsKeyFrame = false
        waitingSinceNanos = null
        started = true
        gap = false
        return true
    }

    /** Queues a config record; the next frame must be a key frame of the new configuration. */
    fun offerConfig(config: WebVideoRecord, now: Long) {
        records.addLast(config)
        requireKeyFrame(now)
    }

    fun poll(now: Long): WebVideoRecord? {
        expire(now)
        val record = records.removeFirstOrNull() ?: return null
        if (record.kind == WebVideoRecords.KIND_FRAME) {
            size--
            bytes -= record.payloadLength
        }
        return record
    }

    /** Drops every queued frame; frames are skipped until the next key frame. */
    fun invalidate(now: Long) {
        records.removeAll { it.kind == WebVideoRecords.KIND_FRAME }
        if (started) gap = true
        size = 0
        bytes = 0
        requireKeyFrame(now)
    }

    /** Waits for a key frame without counting a gap, as at the start of a stream or configuration. */
    fun requireKeyFrame(now: Long) {
        needsKeyFrame = true
        if (waitingSinceNanos == null) waitingSinceNanos = now
    }

    private fun expire(now: Long) {
        val oldest = records.firstOrNull { it.kind == WebVideoRecords.KIND_FRAME } ?: return
        if (now - oldest.queuedAtNanos >= maxAgeNanos) {
            expired += size
            invalidate(now)
        }
    }
}
