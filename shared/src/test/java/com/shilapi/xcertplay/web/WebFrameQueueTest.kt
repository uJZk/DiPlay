// SPDX-License-Identifier: GPL-3.0-only
// Adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
// common/src/test/java/com/shilapi/xcertplay/web/RtcFrameQueueTest.kt at commit c1bd077. Modified for TiPlay, 2026-10.
package com.shilapi.xcertplay.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFrameQueueTest {
    private fun frame(sequence: Int, key: Boolean, now: Long, size: Int = 2) = WebVideoRecord(
        WebVideoRecords.KIND_FRAME, if (key) WebVideoRecords.FLAG_KEY_FRAME else 0, WebVideoRecords.CODEC_H264,
        sequence, 1, 0L, ByteArray(size), queuedAtNanos = now,
    )

    private fun config(epoch: Int) = WebVideoRecord(WebVideoRecords.KIND_CONFIG, 0, 1, 0, epoch, 0L, ByteArray(3))

    @Test fun expirationDropsTheEntireReferenceChainAndWaitsForAKeyFrame() {
        val q = WebFrameQueue(maxAgeNanos = 50)
        assertFalse(q.offer(frame(0, false, 0), 0))
        assertTrue(q.offer(frame(1, true, 0), 0))
        assertTrue(q.offer(frame(2, false, 10), 10))
        assertNull(q.poll(50))
        assertEquals(2L, q.expired)
        assertEquals(0, q.bytes)
        assertFalse(q.offer(frame(3, false, 51), 51))
        assertTrue(q.offer(frame(4, true, 52), 52))
        val next = q.poll(53)!!
        assertEquals(4, next.sequence)
        assertTrue(next.flags and WebVideoRecords.FLAG_DISCONTINUITY != 0)
    }

    @Test fun producerAlsoExpiresOldFramesAndAFreshKeyFrameReplacesThem() {
        val q = WebFrameQueue(maxAgeNanos = 50)
        q.offer(frame(1, true, 0), 0)
        assertTrue(q.offer(frame(2, true, 50), 50))
        assertEquals(1L, q.expired)
        assertEquals(2, q.poll(51)!!.sequence)
    }

    @Test fun overflowNeverContinuesWithPredictiveFrames() {
        val q = WebFrameQueue(maxBytes = 4, maxFrames = 2)
        q.offer(frame(0, true, 0), 0); q.offer(frame(1, false, 1), 1)
        assertFalse(q.offer(frame(2, false, 2), 2))
        assertEquals(2L, q.overflow)
        assertTrue(q.needsKeyFrame)
        assertFalse(q.offer(frame(3, true, 3, size = 5), 3))
        assertTrue(q.offer(frame(4, true, 4), 4))
        q.invalidate(5)
        assertFalse(q.offer(frame(5, false, 5), 5))
        assertEquals(0, q.size)
        assertEquals(2L, q.skipped)
    }

    @Test fun byteBudgetCountsTheParameterSetPrefix() {
        val q = WebFrameQueue(maxBytes = 6, maxFrames = 10)
        val key = WebVideoRecord(
            WebVideoRecords.KIND_FRAME, WebVideoRecords.FLAG_KEY_FRAME or WebVideoRecords.FLAG_PARAMETER_SETS, 1, 0, 1, 0L,
            ByteArray(2), prefix = ByteArray(3), queuedAtNanos = 0,
        )
        assertTrue(q.offer(key, 0))
        assertEquals(5, q.bytes)
        assertFalse(q.offer(frame(1, false, 1), 1))
        assertEquals(1L, q.overflow)
    }

    @Test fun firstKeyFrameIsNotADiscontinuityButOneAfterADropIs() {
        val q = WebFrameQueue()
        assertFalse(q.offer(frame(0, false, 0), 0))
        assertTrue(q.offer(frame(1, true, 1), 1))
        assertEquals(0, q.poll(2)!!.flags and WebVideoRecords.FLAG_DISCONTINUITY)
        assertTrue(q.offer(frame(2, false, 3), 3))
        assertEquals(0, q.poll(4)!!.flags and WebVideoRecords.FLAG_DISCONTINUITY)
        q.invalidate(5)
        assertFalse(q.offer(frame(3, false, 6), 6))
        val key = frame(4, true, 7)
        assertTrue(q.offer(key, 7))
        val sent = q.poll(8)!!
        assertEquals(WebVideoRecords.FLAG_KEY_FRAME or WebVideoRecords.FLAG_DISCONTINUITY, sent.flags)
        assertSame("The payload is never copied", key.payload, sent.payload)
        assertTrue(q.offer(frame(5, false, 9), 9))
        assertEquals(0, q.poll(10)!!.flags and WebVideoRecords.FLAG_DISCONTINUITY)
    }

    @Test fun configRecordsBypassGatingKeepTheirPlaceAndRequireAKeyFrame() {
        val q = WebFrameQueue(maxAgeNanos = 50, maxFrames = 2)
        q.offer(frame(0, true, 0), 0)
        q.offerConfig(config(2), 1)
        assertTrue(q.needsKeyFrame)
        assertFalse(q.offer(frame(1, false, 2), 2))
        assertTrue(q.offer(frame(0, true, 3), 3))
        assertEquals(WebVideoRecords.KIND_FRAME, q.poll(4)!!.kind)
        assertEquals(2, q.poll(4)!!.epoch)
        assertEquals(WebVideoRecords.KIND_FRAME, q.poll(4)!!.kind)
        // Expiry and overflow drop frames only.
        q.offerConfig(config(3), 10)
        assertTrue(q.offer(frame(0, true, 10), 10))
        assertNull(q.poll(100)?.takeIf { it.kind == WebVideoRecords.KIND_FRAME })
        assertEquals(0, q.size)
    }

    @Test fun waitingStartsAtTheFirstSkippedFrameOrRequirementAndEndsAtAKeyFrame() {
        val q = WebFrameQueue()
        assertNull(q.waitingSinceNanos)
        q.offer(frame(0, false, 10), 10)
        assertEquals(10L, q.waitingSinceNanos)
        q.offer(frame(1, false, 20), 20)
        assertEquals(10L, q.waitingSinceNanos)
        q.offer(frame(2, true, 30), 30)
        assertNull(q.waitingSinceNanos)
        q.requireKeyFrame(40)
        assertEquals(40L, q.waitingSinceNanos)
        q.invalidate(50)
        assertEquals(40L, q.waitingSinceNanos)
    }
}
