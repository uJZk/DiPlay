package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class WebMediaSinkTest {
    private val hub = WebVideoHub(Executor { it.run() })
    private val avcc = byteArrayOf(1, 0x64, 0x00, 0x28, -1, -31, 0, 4, 0x67, 0x64, 0x00, 0x28, 1, 0, 2, 0x68, -18)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)

    @Test fun mainScreenFeedsTheHubWithTheNegotiatedCanvas() {
        val sink = WebMediaSink(hub, 1182, 920, 60)
        var requests = 0
        val diagnostics = mutableListOf<String>()
        // CarPlayMediaEngine.onScreen sets both handlers before the stream becomes active.
        sink.setVideoDiagnosticHandler(110) { diagnostics += it }
        sink.setVideoRecoveryHandler(110) { requests++ }
        sink.onScreenStreamActive(110, true)
        sink.onVideoCodec(110, VideoCodec.H264)
        sink.onVideoConfig(110, avcc)
        assertEquals(WebVideoHub.StreamInfo(1182, 920, 60, "avc1.640028", 1), hub.info())

        val subscription = hub.subscribe()
        assertEquals("The hub asks through the engine's recovery handler", 1, requests)
        sink.onVideoFrame(110, idr, 0L, 1L)
        sink.onVideoFrame(110, idr)
        assertEquals(3, generateSequence { subscription.take(0) }.count())
        hub.browserDecoded(1)
        assertEquals(listOf("first frame rendered"), diagnostics)

        sink.onScreenStreamActive(110, false)
        assertNull(hub.info())
    }

    @Test fun otherScreensAreIgnored() {
        val sink = WebMediaSink(hub, 1182, 920, 60)
        var requests = 0
        sink.setVideoRecoveryHandler(111) { requests++ }
        sink.onScreenStreamActive(111, true)
        assertNull(hub.info())
        sink.onScreenStreamActive(110, true)
        sink.onVideoCodec(111, VideoCodec.H265)
        sink.onVideoConfig(111, avcc)
        assertNull("A cluster config never reaches the hub", hub.info()?.codec)
        sink.onVideoCodec(110, VideoCodec.H264)
        sink.onVideoConfig(110, avcc)
        val subscription = hub.subscribe()
        assertEquals(0, requests)
        sink.onVideoFrame(111, idr, 0L, 0L)
        assertEquals(listOf(WebVideoRecords.KIND_CONFIG), generateSequence { subscription.take(0) }.map { it.kind }.toList())
        sink.onScreenStreamActive(111, false)
        assertNotNull(hub.info())
    }

    @Test fun aRetiredSinkCannotEndTheNextSessionsStream() {
        val old = WebMediaSink(hub, 1182, 920, 60)
        old.onScreenStreamActive(110, true)
        val next = WebMediaSink(hub, 1280, 720, 60)
        next.onScreenStreamActive(110, true)
        old.close()
        old.onScreenStreamActive(110, false)
        assertEquals(1280, hub.info()?.width)
        next.close()
        assertNull(hub.info())
    }

    /** restartCarPlay closes the old sink at once; its stack closes later and its screen can still come up meanwhile. */
    @Test fun aClosedSinkNeverTakesTheHubAgain() {
        val old = WebMediaSink(hub, 1182, 920, 60)
        old.close()
        old.onScreenStreamActive(110, true)
        old.onVideoCodec(110, VideoCodec.H264)
        old.onVideoConfig(110, avcc)
        assertNull("A retired session's late screen starts no stream", hub.info())

        val next = WebMediaSink(hub, 1280, 720, 60)
        next.onScreenStreamActive(110, true)
        next.onVideoCodec(110, VideoCodec.H264)
        next.onVideoConfig(110, avcc)
        old.onScreenStreamActive(110, true)
        assertEquals("The next session keeps the hub", WebVideoHub.StreamInfo(1280, 720, 60, "avc1.640028", 1), hub.info())
    }

    @Test fun configBeforeTheCodecIsIgnored() {
        val sink = WebMediaSink(hub, 1182, 920, 60)
        sink.onScreenStreamActive(110, true)
        sink.onVideoConfig(110, avcc)
        assertTrue(hub.info()?.codec == null)
    }
}
