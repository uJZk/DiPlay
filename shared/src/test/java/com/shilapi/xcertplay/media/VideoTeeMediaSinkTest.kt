package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class VideoTeeMediaSinkTest {
    private val calls = mutableListOf<String>()

    private inner class Recording(private val name: String, private val failing: Boolean = false) : MediaSink {
        var frame: ByteArray? = null
        var recovery: (() -> Unit)? = null
        var diagnostics: ((String) -> Unit)? = null

        private fun record(call: String) {
            calls += "$name.$call"
            if (failing) error("tap failed")
        }

        override fun onVideoCodec(type: Int, codec: VideoCodec) = record("codec")
        override fun onVideoConfig(type: Int, codecData: ByteArray) = record("config")
        override fun onVideoFrame(type: Int, naluBytes: ByteArray) { frame = naluBytes; record("frame") }
        override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
            frame = naluBytes
            record("frame@$senderNanos")
        }
        override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) { recovery = handler; record("recovery") }
        override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) { diagnostics = handler; record("diagnostics") }
        override fun onScreenStreamActive(type: Int, active: Boolean) = record("active=$active")
        override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) = record("audioStarted")
        override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) = record("audioRtp")
        override fun onAudioStopped(id: AudioStreamId) = record("audioStopped")
        override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) = record("micStarted")
        override fun onMicrophoneStopped(id: AudioStreamId) = record("micStopped")
        override fun onIapMessage(bytes: ByteArray) = record("iap")
    }

    @Test fun videoGoesToThePrimaryThenTheTapWithTheSameArray() {
        val primary = Recording("primary")
        val tap = Recording("tap")
        val tee = VideoTeeMediaSink(primary, tap)
        val frame = byteArrayOf(0, 0, 0, 1, 0x65)
        val recovery = {}
        val diagnostics = { _: String -> }
        tee.setVideoDiagnosticHandler(110, diagnostics)
        tee.setVideoRecoveryHandler(110, recovery)
        tee.onScreenStreamActive(110, true)
        tee.onVideoCodec(110, VideoCodec.H264)
        tee.onVideoConfig(110, byteArrayOf(1))
        tee.onVideoFrame(110, frame, 7L, 8L)
        tee.onVideoFrame(110, frame)
        tee.onScreenStreamActive(110, false)
        assertEquals(
            listOf("diagnostics", "recovery", "active=true", "codec", "config", "frame@7", "frame", "active=false")
                .flatMap { listOf("primary.$it", "tap.$it") },
            calls,
        )
        assertSame(frame, primary.frame)
        assertSame(frame, tap.frame)
        assertSame(recovery, tap.recovery)
        assertSame(diagnostics, tap.diagnostics)
    }

    @Test fun audioMicrophoneAndIapGoToThePrimaryOnly() {
        val tee = VideoTeeMediaSink(Recording("primary"), Recording("tap"))
        val id = AudioStreamId(100, "media")
        val format = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 96)
        val microphone = MicrophoneConfig("telephony", 16_000, 1, 97, 20, InetAddress.getLoopbackAddress(), 1, ByteArray(32))
        tee.onAudioStarted(id, format, 0)
        tee.onAudioRtp(id, format, ByteArray(4), 0)
        tee.onAudioStopped(id)
        tee.onMicrophoneStarted(id, microphone)
        tee.onMicrophoneStopped(id)
        tee.onIapMessage(ByteArray(2))
        assertEquals(
            listOf("audioStarted", "audioRtp", "audioStopped", "micStarted", "micStopped", "iap").map { "primary.$it" },
            calls,
        )
    }

    @Test fun aFailingTapIsReportedAndNeverStopsThePrimary() {
        val errors = mutableListOf<Throwable>()
        val tee = VideoTeeMediaSink(Recording("primary"), Recording("tap", failing = true), errors::add)
        tee.onScreenStreamActive(110, true)
        tee.onVideoFrame(110, ByteArray(1), 0L, 0L)
        tee.onVideoFrame(110, ByteArray(1), 1L, 0L)
        assertEquals(listOf("active=true", "frame@0", "frame@1").flatMap { listOf("primary.$it", "tap.$it") }, calls)
        assertEquals(3, errors.size)
        assertTrue(errors.all { it is IllegalStateException })
    }
}
