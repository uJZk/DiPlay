package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec

/**
 * Forwards every call to [primary]; the video calls also go to [tap], after [primary]. Audio, microphone and iAP2
 * go to [primary] only. A tap failure is passed to [onTapError] and never reaches the screen stream.
 *
 * Both sinks get the same frame array; neither may modify it.
 */
class VideoTeeMediaSink(
    private val primary: MediaSink,
    private val tap: MediaSink,
    private val onTapError: (Throwable) -> Unit = {},
) : MediaSink by primary {
    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        primary.onVideoCodec(type, codec)
        toTap { onVideoCodec(type, codec) }
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        primary.onVideoConfig(type, codecData)
        toTap { onVideoConfig(type, codecData) }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        primary.onVideoFrame(type, naluBytes)
        toTap { onVideoFrame(type, naluBytes) }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        primary.onVideoFrame(type, naluBytes, senderNanos, arrivalNanos)
        toTap { onVideoFrame(type, naluBytes, senderNanos, arrivalNanos) }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        primary.setVideoRecoveryHandler(type, handler)
        toTap { setVideoRecoveryHandler(type, handler) }
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        primary.setVideoDiagnosticHandler(type, handler)
        toTap { setVideoDiagnosticHandler(type, handler) }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        primary.onScreenStreamActive(type, active)
        toTap { onScreenStreamActive(type, active) }
    }

    private inline fun toTap(call: MediaSink.() -> Unit) {
        try {
            tap.call()
        } catch (error: Exception) {
            onTapError(error)
        }
    }
}
