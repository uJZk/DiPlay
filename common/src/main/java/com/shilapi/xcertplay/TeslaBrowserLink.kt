package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.media.VideoTeeMediaSink
import com.shilapi.xcertplay.web.BrowserLinkServer
import com.shilapi.xcertplay.web.BrowserLinkStatus
import com.shilapi.xcertplay.web.BrowserSize
import com.shilapi.xcertplay.web.BrowserViewport
import com.shilapi.xcertplay.web.WebMediaSink
import com.shilapi.xcertplay.web.WebVideoHub
import java.io.FileNotFoundException
import java.net.BindException
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.floor

/**
 * Phone + browser mode: the link between TiPlay and the car's browser (integration E1, protocol v1). Process-wide.
 *
 * It owns the [hub] that the CarPlay screen feeds ([tee]) and the [BrowserLinkServer] on port 8080 of every IPv4
 * address, which also serves the bundled page from the `play/` assets. [sync] runs the server while the run mode in
 * effect is phone + browser; binding happens on the link's own thread and is retried with a backoff while the port is
 * taken. The page's touches and keys go to the running CarPlay session; its viewport is saved for the next connection,
 * and its "Apply and reconnect" goes to the CarPlay screen ([Host]) on the main thread.
 *
 * Nothing here logs the pairing code, the page's session id or an address.
 */
internal object TeslaBrowserLink {
    sealed interface State {
        data object Stopped : State
        data class Listening(val port: Int) : State
        data object PortInUse : State
        data object Failed : State
    }

    /** What the link needs from a server: [BrowserLinkServer] in the app, a fake in tests. */
    interface Server {
        fun start(): Result<Int>
        fun stop()
    }

    fun interface ServerFactory {
        fun create(hub: WebVideoHub, callbacks: BrowserLinkServer.Callbacks, assets: (String) -> ByteArray?): Server
    }

    /** The CarPlay screen's part: its session log, and the browser's requests, on the main thread. */
    interface Host {
        fun log(message: String)
        /** The driver pressed "Apply and reconnect" in the browser: the CarPlay screen decides whether to reconnect. */
        fun onBrowserFit()
        fun onBrowserViewport(viewport: BrowserViewport) {}
    }

    const val PORT = BrowserLinkServer.DEFAULT_PORT
    const val STATS_LOG_INTERVAL_MILLIS = 10_000L
    /** Where the build puts the copy of `site/play/` (see common/build.gradle.kts). */
    const val ASSET_DIRECTORY = "play"

    private const val TAG = "TiPlayBrowserLink"
    private const val PREFS = "tiplay_browser_link"
    private const val KEY_PAIRING_CODE = "pairing_code"
    private const val KEY_PAGE_ADDRESS = "page_address"
    private const val KEY_VIEWPORT_WIDTH = "viewport_width"
    private const val KEY_VIEWPORT_HEIGHT = "viewport_height"
    private const val KEY_VIEWPORT_CSS_WIDTH = "viewport_css_width"
    private const val KEY_VIEWPORT_CSS_HEIGHT = "viewport_css_height"
    private const val KEY_VIEWPORT_DPR = "viewport_dpr"
    /** The page's `st` fields (the flattened `pageStats()` of site/play/app.js) that the session log keeps. */
    private val STATS_FIELDS = listOf(
        "path" to "link.path",
        "codec" to "video.codec",
        "fps" to "video.decodedFps",
        "decodeP95Ms" to "video.decodeMsP95",
        "gaps" to "video.gaps",
        "dropped" to "video.dropped",
        "errors" to "video.decodeErrors",
        "reconnects" to "link.reconnects",
    )
    private val SAFE_TEXT = Regex("[A-Za-z0-9._-]{1,32}")

    /** The app's server: every IPv4 address, so it answers on the hotspot's own and its extra address alike. */
    val appServers = ServerFactory { hub, callbacks, assets ->
        val server = BrowserLinkServer(hub, callbacks, "0.0.0.0", PORT, assets = assets)
        object : Server {
            override fun start() = server.start()
            override fun stop() = server.stop()
        }
    }

    @Volatile internal var serverFactory: ServerFactory = appServers
    /** 1 s, 2 s, 4 s … up to 30 s between attempts to bind while the port is taken. */
    @Volatile internal var backoffMillis: (Int) -> Long = { failures -> minOf(30_000L, 1_000L shl minOf(failures, 5)) }
    @Volatile internal var clock: () -> Long = SystemClock::elapsedRealtime

    val hub: WebVideoHub by lazy { WebVideoHub(log = ::log) }

    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "tiplay-browser-link").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val random by lazy { SecureRandom() }
    private val requested = AtomicBoolean(false)
    private val host = AtomicReference<Host?>(null)
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val taps = Collections.synchronizedMap(WeakHashMap<MediaSink, WebMediaSink>())
    private val statsLock = Any()

    // Worker thread only.
    private var server: Server? = null
    private var retry: ScheduledFuture<*>? = null
    private var failures = 0

    @Volatile private var app: Context? = null
    @Volatile private var code: String? = null
    @Volatile private var viewport: BrowserViewport? = null
    @Volatile private var viewportLoaded = false
    private var lastStatsLog: Long? = null

    /** What the server is doing; listeners hear every change on the link's thread. */
    @Volatile var state: State = State.Stopped
        private set

    /** True between [start] and [stop]: the link is listening, or binding, or waiting to retry. */
    val active: Boolean get() = requested.get()

    fun addListener(listener: (State) -> Unit) { listeners += listener }

    fun removeListener(listener: (State) -> Unit) { listeners -= listener }

    /** The run mode in effect is phone + browser: a running session keeps the mode it connected with. */
    fun wanted(context: Context): Boolean =
        AirPlayPersistence.isPhoneBrowserMode(context, CarPlayBackgroundSession.snapshot()?.controller)

    /** Starts the link when it is [wanted], otherwise stops it. Cheap and idempotent; safe from any lifecycle callback. */
    fun sync(context: Context) {
        if (wanted(context)) start(context) else stop()
    }

    /** Idempotent. Creates the pairing code on first use; the socket work happens on the link's thread. */
    fun start(context: Context) {
        val app = context.applicationContext
        this.app = app
        pairingCode(app)
        lastViewport(app)
        if (requested.compareAndSet(false, true)) worker.execute(::reconcile)
    }

    /** Idempotent. Ends the browser's video and touches and frees the port, on the link's thread. */
    fun stop() {
        if (requested.compareAndSet(true, false)) worker.execute(::reconcile)
    }

    fun attachHost(host: Host) = this.host.set(host)

    fun detachHost(host: Host) {
        this.host.compareAndSet(host, null)
    }

    /**
     * The engine's sink for a new session: in phone + browser mode the Android [sink] with a tap that feeds the main
     * screen into [hub]; otherwise [sink] itself. [width] × [height] is the negotiated canvas.
     */
    fun tee(sink: MediaSink, phoneBrowser: Boolean, width: Int, height: Int): MediaSink {
        if (!phoneBrowser) return sink
        val web = WebMediaSink(hub, width, height, TeslaBrowserCanvas.FPS)
        taps[sink] = web
        val reported = AtomicBoolean(false)
        return VideoTeeMediaSink(sink, web) { error ->
            // Once per session: a failing tap would otherwise log every frame.
            if (reported.compareAndSet(false, true)) log("Browser link: video tap failed (${error.javaClass.simpleName})")
        }
    }

    /** Ends the browser's stream of [sink]'s session, which is being retired; the next session starts a new one. */
    fun releaseTap(sink: MediaSink) {
        taps.remove(sink)?.close()
    }

    /** The 6-digit pairing code; created (SecureRandom) and saved the first time. */
    fun pairingCode(context: Context): String {
        code?.let { return it }
        synchronized(this) {
            code?.let { return it }
            val prefs = prefs(context)
            val value = prefs.getString(KEY_PAIRING_CODE, null)?.takeIf(::isCode)
                ?: newCode().also { prefs.edit().putString(KEY_PAIRING_CODE, it).apply() }
            code = value
            return value
        }
    }

    fun hasPairingCode(context: Context): Boolean =
        code != null || prefs(context).getString(KEY_PAIRING_CODE, null)?.let(::isCode) == true

    /** Replaces the pairing code and ends the current browser session: the server restarts and refuses the old code. */
    fun newPairingCode(context: Context): String {
        val value = newCode()
        synchronized(this) {
            prefs(context).edit().putString(KEY_PAIRING_CODE, value).apply()
            code = value
        }
        log("Browser link: new pairing code; the browser must pair again")
        if (requested.get()) worker.execute {
            server?.let { stopQuietly(it) }
            server = null
            reconcile()
        }
        return value
    }

    /** The browser's last valid viewport, kept for the next connection; null before any browser reported one. */
    fun lastViewport(context: Context): BrowserViewport? {
        if (viewportLoaded) return viewport
        synchronized(this) {
            if (!viewportLoaded) {
                viewport = loadViewport(context)
                viewportLoaded = true
            }
        }
        return viewport
    }

    /** The HTTPS page the links open; [TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS] unless the driver hosts their own. */
    fun pageAddress(context: Context): String =
        TeslaBrowserPageLinks.savedPageAddress(prefs(context).getString(KEY_PAGE_ADDRESS, null))

    /**
     * Saves [text] when it is a valid page address ([TeslaBrowserPageLinks.pageAddress]); false otherwise. The default
     * is not stored, so a dialog saved unchanged follows the default of a later build.
     */
    fun savePageAddress(context: Context, text: String): Boolean {
        val address = TeslaBrowserPageLinks.pageAddress(text) ?: return false
        val editor = prefs(context).edit()
        if (TeslaBrowserPageLinks.savedPageAddress(address) == TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS) {
            editor.remove(KEY_PAGE_ADDRESS)
        } else {
            editor.putString(KEY_PAGE_ADDRESS, address)
        }
        editor.apply()
        return true
    }

    /**
     * The address the car's browser reaches the phone at: the extra hotspot address when the driver set one, else
     * [hotspotAddress], the hotspot's own IPv4 that the caller looked up off the main thread; null when neither is known.
     */
    fun browserAddress(context: Context, hotspotAddress: String?): String? =
        if (HotspotExtraAddressSettings.enabled(context)) HotspotExtraAddressSettings.address(context).hostAddress else hotspotAddress

    /**
     * The link for the Tesla and other Chromium browsers, naming the phone at [browserAddress] in `?t=` unless it is the
     * page's default phone. Creates the pairing code if needed; no network access.
     */
    fun pageLink(context: Context, hotspotAddress: String?): String = TeslaBrowserPageLinks.pageLink(
        pageAddress(context), pairingCode(context), browserAddress(context, hotspotAddress), PORT)

    /**
     * Why CarPlay should reconnect for the browser's "Apply and reconnect", or null when it should not: no session runs
     * on [running], or its canvas already matches the browser's within 8 px and 1 % in aspect.
     */
    fun fitReason(context: Context, running: BrowserSize?): String? {
        if (running == null) {
            log("Browser link: the browser asked to fit, but no CarPlay session is running")
            return null
        }
        val wanted = TeslaBrowserCanvas.size(lastViewport(context)?.size)
        if (!TeslaBrowserCanvas.differs(running, wanted)) {
            log("Browser link: CarPlay already fits the browser (${running.width}x${running.height})")
            return null
        }
        return "Browser asked to fit: reconnecting CarPlay at ${wanted.width}x${wanted.height} " +
            "(was ${running.width}x${running.height})"
    }

    /** For the diagnostic report: no code, no address. */
    fun diagnosticLines(context: Context): List<String> {
        val link = when (val current = state) {
            is State.Listening -> "listening(port=${current.port})"
            State.PortInUse -> "port-in-use"
            State.Failed -> "failed"
            State.Stopped -> if (active) "starting" else "stopped"
        }
        val size = lastViewport(context)?.let { "${it.width}x${it.height}" } ?: "none"
        return listOf(
            "Run mode (saved; a running session keeps the mode it connected with): " +
                if (AirPlayPersistence.isPhoneBrowserMode(context)) "phone-browser" else "head-unit",
            "Browser link: state=$link lastViewport=$size pairingCode=${if (hasPairingCode(context)) "yes" else "no"} " +
                "localNetworkPermission=${LocalNetworkPermission.state(context).wire} " +
                "customPage=${pageAddress(context) != TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS}",
        )
    }

    /** What the page shows (protocol §4.2). Called on server threads; never blocks. */
    internal fun status(): BrowserLinkStatus {
        val info = hub.info()
        val snapshot = CarPlayBackgroundSession.snapshot()
        val state = when {
            info != null -> BrowserLinkStatus.State.STREAMING
            CarPlayBackgroundSession.hasSession() -> BrowserLinkStatus.State.CONNECTING
            else -> BrowserLinkStatus.State.IDLE
        }
        val app = app
        return BrowserLinkStatus(
            state = state,
            codec = info?.codec,
            display = info?.let { BrowserSize(it.width, it.height) } ?: snapshot?.display?.let { BrowserSize(it.width, it.height) },
            // The canvas "Apply and reconnect" would ask for, so the page offers it only when a reconnect changes something.
            viewport = viewport?.let { TeslaBrowserCanvas.size(it.size) },
            touchAvailable = snapshot != null && CarPlayBackgroundSession.active,
            phone = linkedMapOf("localNetworkPermission" to (app?.let { LocalNetworkPermission.state(it).wire })),
        )
    }

    /** One line for the session log at most every 10 s: numbers and short names only. */
    internal fun statsLine(stats: Map<String, Any?>): String = buildString {
        append("Browser:")
        for ((label, key) in STATS_FIELDS) append(' ').append(label).append('=').append(statValue(stats[key]))
    }

    private val callbacks = object : BrowserLinkServer.Callbacks {
        override fun code(): String? = code
        override fun status(): BrowserLinkStatus = this@TeslaBrowserLink.status()
        override fun onTouch(contacts: List<AirPlayContact>): Boolean =
            CarPlayBackgroundSession.snapshot()?.controller?.sendTouch(contacts) ?: false
        override fun onViewport(viewport: BrowserViewport) = viewportChanged(viewport)
        override fun onFit() {
            main.post {
                val screen = host.get()
                if (screen == null) log("Browser link: the browser asked to fit, but no CarPlay screen is open")
                else screen.onBrowserFit()
            }
        }
        override fun onKey(name: String) {
            val controller = CarPlayBackgroundSession.snapshot()?.controller ?: return
            when (name) {
                "home" -> controller.sendKnob(AirPlayKnobState(home = true))
                "back" -> controller.sendKnob(AirPlayKnobState(back = true))
                "siri" -> controller.requestSiri()
            }
        }
        override fun onBrowserStats(stats: Map<String, Any?>) {
            val now = clock()
            synchronized(statsLock) {
                val last = lastStatsLog
                if (last != null && now - last < STATS_LOG_INTERVAL_MILLIS) return
                lastStatsLog = now
            }
            log(statsLine(stats))
        }
        override fun log(message: String) = this@TeslaBrowserLink.log(message)
    }

    private fun reconcile() {
        retry?.cancel(false)
        retry = null
        if (!requested.get()) {
            server?.let { stopQuietly(it) }
            server = null
            failures = 0
            publish(State.Stopped)
            return
        }
        if (server != null) return
        val candidate = try {
            serverFactory.create(hub, callbacks, ::asset)
        } catch (error: Exception) {
            log("Browser link: server could not be created (${error.javaClass.simpleName})")
            null
        }
        val result = candidate?.start() ?: Result.failure(IllegalStateException("no server"))
        val port = result.getOrNull()
        if (candidate != null && port != null) {
            server = candidate
            failures = 0
            publish(State.Listening(port))
            return
        }
        candidate?.let { stopQuietly(it) }
        publish(if (result.exceptionOrNull() is BindException) State.PortInUse else State.Failed)
        retry = worker.schedule(::reconcile, backoffMillis(failures++), TimeUnit.MILLISECONDS)
    }

    private fun publish(next: State) {
        if (state == next) return
        state = next
        Log.i(TAG, "state=$next")
        for (listener in listeners) {
            try {
                listener(next)
            } catch (error: RuntimeException) {
                Log.w(TAG, "listener failed", error)
            }
        }
    }

    private fun viewportChanged(next: BrowserViewport) {
        if (next == viewport) return
        viewport = next
        viewportLoaded = true
        app?.let { saveViewport(it, next) }
        log("Browser link: viewport ${TeslaBrowserCanvas.describe(next)}")
        main.post { host.get()?.onBrowserViewport(next) }
    }

    private fun asset(name: String): ByteArray? {
        val app = app ?: return null
        return try {
            app.assets.open("$ASSET_DIRECTORY/$name").use { it.readBytes() }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        host.get()?.log(message)
    }

    private fun stopQuietly(server: Server) {
        try {
            server.stop()
        } catch (error: RuntimeException) {
            Log.w(TAG, "server stop failed", error)
        }
    }

    private fun loadViewport(context: Context): BrowserViewport? {
        val prefs = prefs(context)
        val width = prefs.getInt(KEY_VIEWPORT_WIDTH, 0)
        val height = prefs.getInt(KEY_VIEWPORT_HEIGHT, 0)
        if (!BrowserViewport.isValid(width, height)) return null
        return BrowserViewport(width, height, prefs.getFloat(KEY_VIEWPORT_CSS_WIDTH, 0f).toDouble(),
            prefs.getFloat(KEY_VIEWPORT_CSS_HEIGHT, 0f).toDouble(), prefs.getFloat(KEY_VIEWPORT_DPR, 0f).toDouble())
    }

    private fun saveViewport(context: Context, viewport: BrowserViewport) {
        prefs(context).edit()
            .putInt(KEY_VIEWPORT_WIDTH, viewport.width)
            .putInt(KEY_VIEWPORT_HEIGHT, viewport.height)
            .putFloat(KEY_VIEWPORT_CSS_WIDTH, viewport.cssWidth.toFloat())
            .putFloat(KEY_VIEWPORT_CSS_HEIGHT, viewport.cssHeight.toFloat())
            .putFloat(KEY_VIEWPORT_DPR, viewport.devicePixelRatio.toFloat())
            .apply()
    }

    private fun newCode(): String = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))

    private fun isCode(value: String): Boolean = value.length == 6 && value.all { it in '0'..'9' }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun statValue(value: Any?): String = when (value) {
        is Number -> value.toDouble().let { number ->
            when {
                !number.isFinite() -> "-"
                number == floor(number) && abs(number) < 1e12 -> number.toLong().toString()
                else -> String.format(Locale.ROOT, "%.1f", number)
            }
        }
        is Boolean -> value.toString()
        is String -> value.takeIf(SAFE_TEXT::matches) ?: "?"
        else -> "-"
    }

    /** Tests: stop, wait for the link's thread and forget what this process cached. */
    internal fun resetForTest() {
        stop()
        awaitIdle()
        synchronized(this) {
            code = null
            viewport = null
            viewportLoaded = false
        }
        synchronized(statsLock) { lastStatsLog = null }
        host.set(null)
        listeners.clear()
        taps.clear()
        app = null
    }

    /** Tests: waits until the link's thread has run everything queued so far (not a retry scheduled for later). */
    internal fun awaitIdle(timeoutMillis: Long = 5_000): Boolean {
        val done = CountDownLatch(1)
        worker.execute { done.countDown() }
        return done.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}
