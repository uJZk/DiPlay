package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Process-wide hand-off of the main CarPlay screen to the browser link; it outlives AirPlay sessions.
 *
 * One producer at a time: [beginStream] makes its owner (a session's [WebMediaSink]) current, and calls from any
 * other owner are ignored, so an old session's sink cannot touch a newer stream. At most one subscriber (a
 * `/video` response); the latest wins and the previous one ends with `replaced`.
 *
 * The producer calls run on the screen thread and never block, copy or modify a frame. The key-frame request
 * is a blocking network write, so it runs on [keyFrameExecutor].
 *
 * Epochs: the first config of every stream, and every config that differs from the previous one, starts a new
 * epoch. The iPhone repeats a byte-identical config whenever it honours a key-frame request; that keeps the epoch.
 */
class WebVideoHub(
    private val keyFrameExecutor: Executor = Executors.newSingleThreadExecutor { Thread(it, "web-keyframe").apply { isDaemon = true } },
    private val nanoTime: () -> Long = System::nanoTime,
    private val log: (String) -> Unit = {},
) {
    /** The current stream: the negotiated canvas and, once configured, the codec string and epoch. */
    data class StreamInfo(val width: Int, val height: Int, val fps: Int, val codec: String?, val epoch: Int?)

    private class Stream(
        val width: Int,
        val height: Int,
        val fps: Int,
        val requestKeyFrame: () -> Unit,
        val report: (String) -> Unit,
    )

    private class Config(
        val epoch: Int,
        val codec: VideoCodec,
        val codecString: String,
        val parameterSets: ByteArray,
        val message: WebVideoRecord,
    )

    internal val lock = ReentrantLock()
    private val performance = VideoPerformance(nanoTime)
    private var owner: Any? = null
    private var stream: Stream? = null
    private var config: Config? = null
    private var lastCodec: VideoCodec? = null
    private var lastRecord: ByteArray? = null
    private var epochs = 0
    private var sequence = 0
    private var origin = false
    private var originSenderNanos = 0L
    private var originArrivalNanos = 0L
    private var renderedEpoch = 0
    private var subscription: WebVideoSubscription? = null
    private var lastKeyFrameRequestNanos: Long? = null

    /**
     * Makes [owner] the producer. [requestKeyFrame] asks the iPhone for a key frame (blocking); [report] is the
     * stream's video diagnostic handler, which turns `first frame rendered` into the wireless connection proof.
     */
    fun beginStream(owner: Any, width: Int, height: Int, fps: Int, requestKeyFrame: () -> Unit, report: (String) -> Unit) {
        lock.withLock {
            this.owner = owner
            stream = Stream(width, height, fps, requestKeyFrame, report)
            config = null
            lastCodec = null
            lastRecord = null
            lastKeyFrameRequestNanos = null
            subscription?.queue?.requireKeyFrame(nanoTime())
        }
        log("Web video: stream started size=${width}x$height fps=$fps")
    }

    fun configure(owner: Any, codec: VideoCodec, record: ByteArray) {
        val message = lock.withLock {
            if (owner !== this.owner) return
            val stream = stream ?: return
            if (codec == lastCodec && record.contentEquals(lastRecord)) return
            val copy = record.copyOf()
            lastCodec = codec
            lastRecord = copy
            val codecString = WebVideoCodecs.codecString(codec, copy)
            val parameterSets = WebVideoCodecs.parameterSets(codec, copy)
            if (codecString == null || parameterSets.isEmpty()) {
                config = null
                return@withLock "Web video: unsupported $codec config recordBytes=${copy.size}"
            }
            val epoch = ++epochs
            val payload = WebVideoRecords.configPayload(
                codecString, stream.width, stream.height, stream.fps, codec, WebVideoCodecs.nalLengthSize(codec, copy), copy,
            )
            val next = Config(epoch, codec, codecString, parameterSets, WebVideoRecords.config(epoch, codec, payload))
            config = next
            sequence = 0
            origin = false
            subscription?.let {
                it.queue.offerConfig(next.message, nanoTime())
                it.signal()
            }
            "Web video: config epoch=$epoch codec=$codecString"
        }
        log(message)
    }

    /** One Annex-B access unit. [annexB] is shared with the Android decoder: it is queued as is, never copied or changed. */
    fun frame(owner: Any, annexB: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        val force = lock.withLock {
            if (owner !== this.owner) return
            val config = config ?: return
            val now = nanoTime()
            val sequence = sequence++
            if (!origin) {
                origin = true
                originSenderNanos = senderNanos
                originArrivalNanos = arrivalNanos
            }
            // The iPhone's frame time when it has one, else the arrival time; both relative to the epoch's first frame.
            val timestampUs = if (senderNanos != 0L && originSenderNanos != 0L) (senderNanos - originSenderNanos) / 1000
            else (arrivalNanos - originArrivalNanos) / 1000
            performance.count("input")
            val subscription = subscription ?: return
            val record = if (WebVideoCodecs.isKeyFrame(config.codec, annexB)) {
                WebVideoRecord(
                    WebVideoRecords.KIND_FRAME, WebVideoRecords.FLAG_KEY_FRAME or WebVideoRecords.FLAG_PARAMETER_SETS,
                    WebVideoRecords.codecId(config.codec), sequence, config.epoch, timestampUs, annexB, config.parameterSets, now,
                )
            } else {
                WebVideoRecord(
                    WebVideoRecords.KIND_FRAME, 0, WebVideoRecords.codecId(config.codec), sequence, config.epoch, timestampUs,
                    annexB, null, now,
                )
            }
            if (subscription.queue.offer(record, now)) subscription.signal()
            keyFrameDue(subscription, now)
        } ?: return
        requestKeyFrame(force)
    }

    fun endStream(owner: Any) {
        lock.withLock {
            if (owner !== this.owner) return
            this.owner = null
            stream = null
            config = null
            lastCodec = null
            lastRecord = null
            subscription?.queue?.requireKeyFrame(nanoTime())
        }
        log("Web video: stream ended")
    }

    /** Asks the current stream for a key frame, at most once per 500 ms unless [force]. */
    fun requestKeyFrame(force: Boolean = false) {
        val request = lock.withLock {
            val stream = stream ?: return
            val now = nanoTime()
            val last = lastKeyFrameRequestNanos
            if (!force && last != null && now - last < KEY_FRAME_INTERVAL_NANOS) return
            lastKeyFrameRequestNanos = now
            performance.count("keyFrameRequests")
            stream.requestKeyFrame
        }
        try {
            keyFrameExecutor.execute {
                try {
                    request()
                } catch (error: Exception) {
                    log("Web video: key frame request failed ${error.javaClass.simpleName}")
                }
            }
        } catch (_: RejectedExecutionException) {
        }
    }

    /** The page decoded and drew the first frame of [epoch]: report `first frame rendered` once per epoch. */
    fun browserDecoded(epoch: Int) {
        val report = lock.withLock {
            val config = config ?: return
            if (epoch != config.epoch || epoch == renderedEpoch) return
            renderedEpoch = epoch
            stream?.report ?: return
        }
        log("Web video: browser rendered epoch=$epoch")
        report(FIRST_FRAME_RENDERED)
    }

    fun info(): StreamInfo? = lock.withLock {
        stream?.let { StreamInfo(it.width, it.height, it.fps, config?.codecString, config?.epoch) }
    }

    /** Counters for the link status; no payloads. */
    fun snapshot(): Map<String, Any?> {
        val counters = lock.withLock {
            val queue = subscription?.queue
            linkedMapOf<String, Any?>(
                "epoch" to config?.epoch,
                "viewer" to (queue != null),
                "queueFrames" to queue?.size,
                "queueBytes" to queue?.bytes,
                "expiredFrames" to queue?.expired,
                "overflowFrames" to queue?.overflow,
                "skippedFrames" to queue?.skipped,
            )
        }
        // Outside the lock: summarising the timings must not hold up the screen thread.
        return counters + ("performance" to performance.snapshot())
    }

    /** Starts a subscription: it gets the current config first, then frames from the next key frame, which is requested now. */
    internal fun subscribe(): WebVideoSubscription {
        val subscription = lock.withLock {
            val now = nanoTime()
            subscription?.endLocked("replaced")
            WebVideoSubscription(this, lock.newCondition(), now).also { next ->
                config?.let { next.queue.offerConfig(it.message, now) }
                subscription = next
            }
        }
        requestKeyFrame(force = true)
        return subscription
    }

    internal fun unsubscribe(subscription: WebVideoSubscription) = lock.withLock {
        if (this.subscription === subscription) this.subscription = null
    }

    /** Re-asks for a key frame while [subscription] waits for one; the writer calls it at least once per second. */
    internal fun maintain(subscription: WebVideoSubscription) {
        val force = lock.withLock {
            if (this.subscription !== subscription) return
            keyFrameDue(subscription, nanoTime())
        } ?: return
        requestKeyFrame(force)
    }

    internal fun now(): Long = nanoTime()

    /**
     * Null when no request is due. A wait that began after the last request asks once (rate-limited);
     * a wait that outlasts its request by [KEY_FRAME_RETRY_NANOS] forces another.
     */
    private fun keyFrameDue(subscription: WebVideoSubscription, now: Long): Boolean? {
        val waitingSince = subscription.queue.waitingSinceNanos ?: return null
        if (stream == null) return null
        val last = lastKeyFrameRequestNanos
        return when {
            last == null || last < waitingSince -> false
            now - last >= KEY_FRAME_RETRY_NANOS -> true
            else -> null
        }
    }

    companion object {
        const val FIRST_FRAME_RENDERED = "first frame rendered"
        private const val KEY_FRAME_INTERVAL_NANOS = 500_000_000L
        private const val KEY_FRAME_RETRY_NANOS = 3_000_000_000L
    }
}

/** One `/video` writer's view of the [WebVideoHub]. Its state is guarded by the hub's lock. */
internal class WebVideoSubscription(
    private val hub: WebVideoHub,
    private val available: Condition,
    now: Long,
) : Closeable {
    val queue = WebFrameQueue().apply { requireKeyFrame(now) }
    private var end: WebVideoRecord? = null

    /**
     * The next record to write, or null after [timeoutMillis] without one. Once the subscription was ended
     * this returns the end record, every time. Blocks only the calling writer thread.
     */
    fun take(timeoutMillis: Long): WebVideoRecord? {
        hub.maintain(this)
        hub.lock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (true) {
                end?.let { return it }
                queue.poll(hub.now())?.let { return it }
                if (remaining <= 0L) return null
                remaining = available.awaitNanos(remaining)
            }
        }
    }

    /** The writer sends an end record with [reason] next, then closes. */
    fun end(reason: String) = hub.lock.withLock { endLocked(reason) }

    internal fun endLocked(reason: String) {
        if (end == null) end = WebVideoRecords.end(reason)
        available.signalAll()
    }

    internal fun signal() = available.signalAll()

    override fun close() {
        end("stopped")
        hub.unsubscribe(this)
    }
}
