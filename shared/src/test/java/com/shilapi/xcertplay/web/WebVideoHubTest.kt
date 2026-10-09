package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class WebVideoHubTest {
    private var now = 10_000_000_000L
    private val hub = WebVideoHub(Executor { it.run() }, { now })
    private val owner = Any()
    private var requests = 0
    private val reports = mutableListOf<String>()

    private val avcc = byteArrayOf(1, 0x64, 0x00, 0x28, -1, -31, 0, 4, 0x67, 0x64, 0x00, 0x28, 1, 0, 2, 0x68, -18)
    private val parameterSets = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0, 0, 0, 1, 0x68, -18)
    private fun idr() = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
    private fun delta() = byteArrayOf(0, 0, 0, 1, 0x41, 4, 5)

    private fun begin(owner: Any = this.owner, onRequest: () -> Unit = { requests++ }, onReport: (String) -> Unit = { reports += it }) =
        hub.beginStream(owner, 1182, 920, 60, onRequest, onReport)

    private fun advance(millis: Long) { now += millis * 1_000_000 }

    private fun WebVideoSubscription.drain(): List<WebVideoRecord> = generateSequence { take(0) }.toList()

    @Test fun lateSubscriberGetsTheConfigFirstThenFramesFromTheNextKeyFrame() {
        begin()
        hub.configure(owner, VideoCodec.H264, avcc)
        hub.frame(owner, idr(), 0, now)
        hub.frame(owner, delta(), 0, now)
        assertEquals(0, requests)

        val subscription = hub.subscribe()
        assertEquals("Subscribing forces a key-frame request", 1, requests)
        hub.frame(owner, delta(), 0, now)
        hub.frame(owner, idr(), 0, now)
        hub.frame(owner, delta(), 0, now)
        val records = subscription.drain()
        assertEquals(listOf(WebVideoRecords.KIND_CONFIG, WebVideoRecords.KIND_FRAME, WebVideoRecords.KIND_FRAME), records.map { it.kind })
        val config = String(records[0].payload, Charsets.UTF_8)
        assertTrue(config, config.startsWith("""{"codec":"avc1.640028","width":1182,"height":920,"fps":60,"format":"annexb""""))
        assertEquals(1, records[0].epoch)
        assertEquals(WebVideoRecords.FLAG_KEY_FRAME or WebVideoRecords.FLAG_PARAMETER_SETS, records[1].flags)
        assertEquals(listOf(3, 4), records.drop(1).map { it.sequence })
    }

    @Test fun keyFramesCarryParameterSetsAndFramesAreNeitherCopiedNorChanged() {
        begin()
        hub.configure(owner, VideoCodec.H264, avcc)
        val subscription = hub.subscribe()
        val key = idr()
        val original = key.copyOf()
        hub.frame(owner, key, 0, now)
        val next = delta()
        hub.frame(owner, next, 0, now)
        val (_, keyRecord, deltaRecord) = subscription.drain()
        assertSame(key, keyRecord.payload)
        assertArrayEquals(original, key)
        assertArrayEquals(parameterSets, keyRecord.prefix)
        assertEquals(WebVideoRecords.CODEC_H264, keyRecord.codec)
        assertSame(next, deltaRecord.payload)
        assertNull(deltaRecord.prefix)
        assertEquals(0, deltaRecord.flags)
    }

    @Test fun sequenceCountsEveryFrameAndTimestampsAreRelativeToTheEpoch() {
        begin()
        hub.configure(owner, VideoCodec.H264, avcc)
        hub.frame(owner, delta(), 1_000_000_000L, now) // before the subscription: still counted
        val subscription = hub.subscribe()
        hub.frame(owner, idr(), 1_016_666_667L, now)
        hub.frame(owner, delta(), 1_050_000_000L, now)
        val frames = subscription.drain().filter { it.kind == WebVideoRecords.KIND_FRAME }
        assertEquals(listOf(1, 2), frames.map { it.sequence })
        assertEquals(listOf(16_666L, 50_000L), frames.map { it.timestampUs })

        // Without the iPhone's frame time the arrival time is used, relative to the epoch's first frame.
        hub.configure(owner, VideoCodec.H264, avcc.copyOf().apply { this[3] = 0x29; this[11] = 0x29 })
        hub.frame(owner, idr(), 0, 7_000_000L)
        hub.frame(owner, delta(), 0, 7_020_000L)
        val second = subscription.drain().filter { it.kind == WebVideoRecords.KIND_FRAME }
        assertEquals(listOf(0, 1), second.map { it.sequence })
        assertEquals(listOf(0L, 20L), second.map { it.timestampUs })
        assertEquals(listOf(2, 2), second.map { it.epoch })
    }

    @Test fun identicalConfigKeepsTheEpochAndAChangeOrANewStreamStartsOne() {
        begin()
        val subscription = hub.subscribe()
        hub.configure(owner, VideoCodec.H264, avcc)
        hub.configure(owner, VideoCodec.H264, avcc.copyOf()) // repeated after forceKeyFrame
        assertEquals(listOf(1), subscription.drain().map { it.epoch })
        assertEquals(1, hub.info()?.epoch)

        val changed = avcc.copyOf().apply { this[3] = 0x2a; this[11] = 0x2a }
        hub.configure(owner, VideoCodec.H264, changed)
        val config = subscription.drain().single()
        assertEquals(2, config.epoch)
        assertTrue(String(config.payload).contains("avc1.64002a"))
        assertEquals("avc1.64002a", hub.info()?.codec)

        // The next stream starts a new epoch even with byte-identical config, so it gets its own rendered report.
        begin()
        hub.configure(owner, VideoCodec.H264, changed)
        assertEquals(3, hub.info()?.epoch)
        assertEquals(listOf(3), subscription.drain().map { it.epoch })
    }

    @Test fun keyFrameRequestsAreRateLimitedUnlessForced() {
        hub.requestKeyFrame(force = true) // no stream yet: nothing to ask
        begin()
        hub.requestKeyFrame()
        advance(100)
        hub.requestKeyFrame()
        assertEquals(1, requests)
        advance(400)
        hub.requestKeyFrame()
        assertEquals(2, requests)
        hub.requestKeyFrame(force = true)
        assertEquals(3, requests)
    }

    @Test fun keyFrameRequestsRunOnTheExecutorAndAFailingRequesterIsContained() {
        val queued = mutableListOf<Runnable>()
        val logs = mutableListOf<String>()
        val hub = WebVideoHub(Executor { queued += it }, { now }, logs::add)
        hub.beginStream(owner, 1182, 920, 60, { error("event channel gone") }, {})
        hub.requestKeyFrame()
        assertEquals(1, queued.size)
        queued.single().run()
        assertTrue(logs.any { it.startsWith("Web video: key frame request failed") })
    }

    @Test fun aWaitingSubscriberAsksOnceAndReForcesEveryThreeSeconds() {
        begin()
        hub.configure(owner, VideoCodec.H264, avcc)
        val subscription = hub.subscribe()
        assertEquals(1, requests)
        repeat(29) { advance(100); hub.frame(owner, delta(), 0, now) }
        assertEquals("Deltas while waiting do not repeat the request", 1, requests)
        advance(100); hub.frame(owner, delta(), 0, now)
        assertEquals(2, requests)
        advance(100); hub.frame(owner, delta(), 0, now)
        assertEquals(2, requests)
        // With no frames arriving at all, the writer's take() keeps re-forcing.
        advance(3_000)
        subscription.take(0)
        assertEquals(3, requests)
        hub.frame(owner, idr(), 0, now)
        subscription.drain()
        advance(5_000)
        hub.frame(owner, delta(), 0, now)
        assertEquals(WebVideoRecords.KIND_FRAME, subscription.take(0)?.kind)
        assertEquals("No requests once a key frame arrived", 3, requests)
    }

    @Test fun aDropAsksForAKeyFrameAndTheNextKeyFrameIsMarkedDiscontinuous() {
        begin()
        hub.configure(owner, VideoCodec.H264, avcc)
        val subscription = hub.subscribe()
        advance(600)
        hub.frame(owner, idr(), 0, now)
        advance(100) // younger than the 150 ms age limit
        repeat(10) { hub.frame(owner, delta(), 0, now) } // the 10th overflows the 10-frame queue
        assertEquals(2, requests)
        repeat(5) { hub.frame(owner, delta(), 0, now) }
        assertEquals("One request per wait", 2, requests)
        hub.frame(owner, idr(), 0, now)
        val records = subscription.drain()
        assertEquals(listOf(WebVideoRecords.KIND_CONFIG, WebVideoRecords.KIND_FRAME), records.map { it.kind })
        val key = records.last()
        assertEquals(WebVideoRecords.FLAG_KEY_FRAME or WebVideoRecords.FLAG_PARAMETER_SETS or WebVideoRecords.FLAG_DISCONTINUITY, key.flags)
        assertEquals(16, key.sequence)
        assertEquals(10L, hub.snapshot()["overflowFrames"])
        assertEquals(6L, hub.snapshot()["skippedFrames"])
    }

    @Test fun browserRenderedIsReportedOncePerEpochToTheCurrentOwner() {
        begin()
        hub.configure(owner, VideoCodec.H264, avcc)
        hub.browserDecoded(2)
        hub.browserDecoded(1)
        hub.browserDecoded(1)
        assertEquals(listOf(WebVideoHub.FIRST_FRAME_RENDERED), reports)
        assertEquals("first frame rendered", WebVideoHub.FIRST_FRAME_RENDERED)
        hub.configure(owner, VideoCodec.H264, avcc.copyOf().apply { this[3] = 0x29; this[11] = 0x29 })
        hub.browserDecoded(1)
        assertEquals(1, reports.size)
        hub.browserDecoded(2)
        assertEquals(2, reports.size)
    }

    @Test fun anOldSessionsSinkIsIgnoredOnceANewOneBegins() {
        val old = Any()
        val oldReports = mutableListOf<String>()
        var oldRequests = 0
        begin(old, { oldRequests++ }, { oldReports += it })
        hub.configure(old, VideoCodec.H264, avcc)
        begin(owner)
        val subscription = hub.subscribe()
        assertEquals(0, oldRequests)
        assertEquals(1, requests)

        hub.configure(old, VideoCodec.H264, avcc)
        hub.frame(old, idr(), 0, now)
        hub.endStream(old)
        assertTrue(subscription.drain().isEmpty())
        assertNull(hub.info()?.codec)
        assertNotNull("The old sink cannot end the new stream", hub.info())

        hub.configure(owner, VideoCodec.H264, avcc)
        hub.browserDecoded(hub.info()!!.epoch!!)
        assertTrue(oldReports.isEmpty())
        assertEquals(listOf(WebVideoHub.FIRST_FRAME_RENDERED), reports)
    }

    @Test fun theLatestSubscriberWins() {
        begin()
        hub.configure(owner, VideoCodec.H264, avcc)
        val first = hub.subscribe()
        val second = hub.subscribe()
        val end = first.take(0)!!
        assertEquals(WebVideoRecords.KIND_END, end.kind)
        assertEquals("replaced", String(end.payload))
        assertSame("The end record repeats", end, first.take(0))
        hub.frame(owner, idr(), 0, now)
        assertEquals(listOf(WebVideoRecords.KIND_CONFIG, WebVideoRecords.KIND_FRAME), second.drain().map { it.kind })
        first.close()
        hub.frame(owner, delta(), 0, now)
        assertEquals("Closing a replaced subscription leaves the current one", 1, second.drain().size)
        second.close()
        assertEquals(false, hub.snapshot()["viewer"])
    }

    @Test fun unsupportedConfigDropsFramesUntilAValidOne() {
        val logs = mutableListOf<String>()
        val hub = WebVideoHub(Executor { it.run() }, { now }, logs::add)
        hub.beginStream(owner, 1182, 920, 60, {}, {})
        val subscription = hub.subscribe()
        hub.configure(owner, VideoCodec.H265, avcc)
        hub.configure(owner, VideoCodec.H265, avcc)
        hub.frame(owner, idr(), 0, now)
        assertTrue(subscription.drain().isEmpty())
        assertNull(hub.info()?.codec)
        assertEquals(1, logs.count { it.startsWith("Web video: unsupported H265 config") })
        hub.configure(owner, VideoCodec.H264, avcc)
        hub.frame(owner, idr(), 0, now)
        assertEquals(2, subscription.drain().size)
    }

    @Test fun anEndedStreamClearsTheConfigAndTheNextStreamStartsAtAKeyFrame() {
        begin()
        hub.configure(owner, VideoCodec.H264, avcc)
        val subscription = hub.subscribe()
        hub.frame(owner, idr(), 0, now)
        hub.endStream(owner)
        assertNull(hub.info())
        hub.frame(owner, delta(), 0, now)
        hub.requestKeyFrame(force = true)
        assertEquals(1, requests)

        begin()
        hub.configure(owner, VideoCodec.H264, avcc)
        hub.frame(owner, delta(), 0, now)
        hub.frame(owner, idr(), 0, now)
        val records = subscription.drain()
        assertEquals(listOf(1, 1, 2, 2), records.map { it.epoch })
        assertEquals(
            listOf(WebVideoRecords.KIND_CONFIG, WebVideoRecords.KIND_FRAME, WebVideoRecords.KIND_CONFIG, WebVideoRecords.KIND_FRAME),
            records.map { it.kind },
        )
        assertTrue(records.last().keyFrame)
        assertEquals(2, requests)
    }

    @Test fun takeWaitsForARecordOrTimesOut() {
        begin()
        hub.configure(owner, VideoCodec.H264, avcc)
        val subscription = hub.subscribe()
        assertEquals(WebVideoRecords.KIND_CONFIG, subscription.take(0)?.kind)
        val started = System.nanoTime()
        assertNull(subscription.take(50))
        assertTrue(System.nanoTime() - started >= 40_000_000)
        val producer = Thread {
            Thread.sleep(50)
            hub.frame(owner, idr(), 0, now)
        }
        producer.start()
        assertEquals(WebVideoRecords.KIND_FRAME, subscription.take(5_000)?.kind)
        producer.join()
        val ender = Thread {
            Thread.sleep(50)
            subscription.end("stopped")
        }
        ender.start()
        assertEquals(WebVideoRecords.KIND_END, subscription.take(5_000)?.kind)
        ender.join()
    }
}
