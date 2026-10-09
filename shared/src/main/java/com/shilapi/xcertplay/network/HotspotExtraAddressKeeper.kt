package com.shilapi.xcertplay.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Keeps the extra 100.64.0.0/10 address on this phone's own hotspot while TiPlay runs in phone + browser mode, so a
 * car browser that opens only such addresses (Tesla) can reach the phone. Tethering drops the address whenever the
 * hotspot restarts, so the keeper watches the tethering and Wi-Fi AP broadcasts, polls every 5 s and adds it again.
 *
 * Needs root. The settings toggle makes the first `su` call ([checkRoot]); the keeper only calls `su` when the address
 * is missing, stops trying once root is refused, and backs off from 5 s to 60 s after failures. All work runs on one
 * background thread; [start] and [stop] return at once.
 */
object HotspotExtraAddressKeeper {
    sealed interface State {
        data object Off : State
        data object NoHotspot : State
        data class Added(val iface: String) : State
        data object RootDenied : State
        data class Failed(val reason: String) : State
    }

    private const val TAG = "TiPlay-HotspotAddress"
    private const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
    private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"

    @Volatile var state: State = State.Off
        private set

    /** True from [start] to [stop], including while root is refused. */
    val running: Boolean get() = session != null

    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val lock = Any()
    @Volatile private var session: Session? = null
    // A refusal outlives the session that met it: a later start (activity resume, next connection) does not ask
    // again; only the settings toggle does, through [start] with retryRootDenied.
    @Volatile internal var rootRefused = false
    @Volatile internal var dependencies = Dependencies()
    private val worker: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1) { task -> Thread(task, "tiplay-hotspot-address").apply { isDaemon = true } }
            .apply { removeOnCancelPolicy = true }
    }

    /**
     * Starts keeping [address], or checks again at once when it already does. A different address replaces the old one,
     * which is removed. [eligible] is read before every check (phone + browser mode, setting on, manual hotspot link).
     * [retryRootDenied] is for the settings toggle after a successful [checkRoot]; other callers leave a refusal alone.
     */
    fun start(context: Context, address: Inet4Address, eligible: () -> Boolean = { true },
        retryRootDenied: Boolean = false) {
        require(HotspotExtraAddress.isCgnat(address)) { "address must be in 100.64.0.0/10" }
        synchronized(lock) {
            if (retryRootDenied) rootRefused = false
            val current = session
            if (current != null && current.address == address) {
                current.refresh(retryRootDenied)
                return
            }
            current?.close(removeAddress = true)
            session = Session(context.applicationContext, address, eligible, dependencies, rootRefused)
                .also(Session::open)
        }
    }

    /**
     * Stops watching. [removeAddress] deletes the address once, best effort: only when the driver turned the feature
     * off. A session that ends keeps it, or the car's page would be cut off until the next session.
     */
    fun stop(removeAddress: Boolean) {
        synchronized(lock) {
            val current = session ?: return
            session = null
            current.close(removeAddress)
        }
        publish(null, State.Off)
    }

    /** Checks again now, for example when the settings page opens. */
    fun checkNow() {
        session?.refresh(retryRootDenied = false)
    }

    /**
     * Blocking: asks for root once (the root manager may show its prompt) and reports whether uid 0 answered. A grant
     * forgets an earlier refusal, even when the keeper starts only at the next connection.
     */
    fun checkRoot(): Boolean = HotspotExtraAddress.rootGranted(
        dependencies.shell(HotspotExtraAddress.ROOT_CHECK_SCRIPT, RootShell.PROMPT_TIMEOUT_MILLIS),
    ).also { granted -> if (granted) rootRefused = false }

    /** [listener] runs on a background thread. */
    fun addListener(listener: (State) -> Unit) { listeners += listener }

    fun removeListener(listener: (State) -> Unit) { listeners -= listener }

    private fun publish(source: Session?, next: State) {
        synchronized(lock) {
            if (source != null && session !== source) return
            if (state == next) return
            state = next
        }
        listeners.forEach { runCatching { it(next) } }
    }

    internal interface HotspotFinder : Closeable {
        /** The hotspot interface name, or null when no hotspot is up. Blocking. */
        fun find(): String?
    }

    internal class Dependencies(
        val shell: (String, Long) -> RootShell.Result = { script, timeout -> RootShell.run(script, timeout) },
        val hasAddress: (String, Inet4Address) -> Boolean = HotspotExtraAddress::interfaceHasAddress,
        val openFinder: (Context) -> HotspotFinder = ::AndroidHotspotFinder,
        val nowMillis: () -> Long = SystemClock::elapsedRealtime,
        val log: (String) -> Unit = { message -> runCatching { Log.i(TAG, message) } },
    )

    private class AndroidHotspotFinder(context: Context) : HotspotFinder {
        // One instance per session, so the tethering callback (API 36+) has reported before later samples.
        private val interfaces = ManualHotspotInterfaces(context)
        override fun find(): String? = HotspotAddresses.current(interfaces.sample())?.iface
        override fun close() = interfaces.close()
    }

    private class Session(
        private val context: Context,
        val address: Inet4Address,
        eligible: () -> Boolean,
        private val dependencies: Dependencies,
        rootDenied: Boolean,
    ) {
        @Volatile private var closed = false
        // Worker thread only.
        private var finder: HotspotFinder? = null
        private var pending: ScheduledFuture<*>? = null
        private val loop = HotspotExtraAddressLoop(
            address = address,
            shell = dependencies.shell,
            hasAddress = dependencies.hasAddress,
            findIface = { (finder ?: dependencies.openFinder(context).also { finder = it }).find() },
            eligible = eligible,
            nowMillis = dependencies.nowMillis,
            log = dependencies.log,
            rootDenied = rootDenied,
        )
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // The sticky state arrives on registration; the first check already covers it.
                if (!isInitialStickyBroadcast) refresh(retryRootDenied = false)
            }
        }
        private var registered = false

        fun open() {
            val filter = IntentFilter().apply {
                addAction(ACTION_TETHER_STATE_CHANGED)
                addAction(ACTION_WIFI_AP_STATE_CHANGED)
            }
            registered = runCatching {
                // Protected system broadcasts, sent by the tethering and Wi-Fi modules, which do not run as the system
                // uid: a non-exported receiver would miss them. They only trigger a check, so nothing is trusted.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
            }.onFailure { dependencies.log("hotspot broadcasts unavailable; polling only: ${it.javaClass.simpleName}") }
                .isSuccess
            post { tick() }
        }

        fun refresh(retryRootDenied: Boolean) = post {
            if (retryRootDenied) loop.clearRootDenied()
            tick()
        }

        fun close(removeAddress: Boolean) {
            closed = true
            if (registered) {
                registered = false
                runCatching { context.unregisterReceiver(receiver) }
            }
            post(evenWhenClosed = true) {
                pending?.cancel(false)
                pending = null
                if (removeAddress) runCatching { loop.remove() }.onFailure { dependencies.log("remove failed: $it") }
                runCatching { finder?.close() }
                finder = null
            }
        }

        private fun tick() {
            if (closed) return
            pending?.cancel(false)
            pending = null
            val next = runCatching { loop.tick() }.getOrElse {
                dependencies.log("check failed: ${it.javaClass.simpleName}")
                HotspotExtraAddressLoop.POLL_MILLIS
            }
            if (!closed) rootRefused = loop.state == State.RootDenied
            publish(this, loop.state)
            if (next != null && !closed) {
                pending = runCatching { worker.schedule({ tick() }, next, TimeUnit.MILLISECONDS) }.getOrNull()
            }
        }

        private fun post(evenWhenClosed: Boolean = false, block: () -> Unit) {
            if (closed && !evenWhenClosed) return
            try {
                worker.execute { if (evenWhenClosed || !closed) block() }
            } catch (_: RejectedExecutionException) {
                // The worker never shuts down; nothing to do if it did.
            }
        }
    }
}

/**
 * One keeper session's decisions, without Android: find the hotspot, add the address when it is missing, and pace the
 * root calls. Not thread-safe; the keeper runs it on its single worker thread.
 */
internal class HotspotExtraAddressLoop(
    val address: Inet4Address,
    private val shell: (String, Long) -> RootShell.Result,
    private val hasAddress: (String, Inet4Address) -> Boolean,
    private val findIface: () -> String?,
    private val eligible: () -> Boolean,
    private val nowMillis: () -> Long,
    private val log: (String) -> Unit = {},
    /** Root was refused before this session: it starts stopped, as the refusing session ended. */
    rootDenied: Boolean = false,
) {
    var state: HotspotExtraAddressKeeper.State =
        if (rootDenied) HotspotExtraAddressKeeper.State.RootDenied else HotspotExtraAddressKeeper.State.Off
        private set
    private var failures = 0
    private var retryAtMillis: Long? = null
    // Where this session last saw the address, for [remove].
    private var knownIface: String? = null

    /** One check. Returns the delay before the next one, or null once root was refused. */
    fun tick(): Long? {
        if (state == HotspotExtraAddressKeeper.State.RootDenied) return null
        if (!runCatching(eligible).getOrDefault(false)) {
            state = HotspotExtraAddressKeeper.State.Off
            return POLL_MILLIS
        }
        val iface = runCatching(findIface).getOrNull()?.takeIf(HotspotExtraAddress::isValidInterfaceName)
        if (iface == null) {
            // A new hotspot gets a fresh start.
            failures = 0
            retryAtMillis = null
            state = HotspotExtraAddressKeeper.State.NoHotspot
            return POLL_MILLIS
        }
        if (hasAddress(iface, address)) return added(iface)
        retryAtMillis?.let { retryAt ->
            val wait = retryAt - nowMillis()
            if (wait > 0) return wait.coerceAtMost(POLL_MILLIS)
        }
        return when (val result = shell(HotspotExtraAddress.addScript(iface, address), RootShell.DEFAULT_TIMEOUT_MILLIS)) {
            RootShell.Result.Unavailable -> {
                state = HotspotExtraAddressKeeper.State.RootDenied
                log("root refused; stopped until the setting is turned on again")
                null
            }
            RootShell.Result.TimedOut -> failed("root shell timed out")
            is RootShell.Result.Done -> when {
                result.exitCode == 0 && hasAddress(iface, address) -> {
                    log("address added to $iface")
                    added(iface)
                }
                result.exitCode == 0 -> failed("address missing after ip on $iface")
                else -> failed("ip exited with ${result.exitCode} on $iface")
            }
        }
    }

    fun clearRootDenied() {
        if (state != HotspotExtraAddressKeeper.State.RootDenied) return
        state = HotspotExtraAddressKeeper.State.Off
        failures = 0
        retryAtMillis = null
    }

    /** Deletes the address once from where this session saw it; skipped when it is gone or root was refused. */
    fun remove(): Boolean {
        val iface = knownIface ?: return false
        knownIface = null
        if (state == HotspotExtraAddressKeeper.State.RootDenied || !hasAddress(iface, address)) return false
        val result = shell(HotspotExtraAddress.deleteScript(iface, address), RootShell.DEFAULT_TIMEOUT_MILLIS)
        val removed = result is RootShell.Result.Done && result.exitCode == 0
        log(if (removed) "address removed from $iface" else "could not remove the address from $iface: $result")
        state = HotspotExtraAddressKeeper.State.Off
        return removed
    }

    private fun added(iface: String): Long {
        knownIface = iface
        failures = 0
        retryAtMillis = null
        state = HotspotExtraAddressKeeper.State.Added(iface)
        return POLL_MILLIS
    }

    private fun failed(reason: String): Long {
        failures++
        val delay = backoffMillis(failures)
        retryAtMillis = nowMillis() + delay
        state = HotspotExtraAddressKeeper.State.Failed(reason)
        log("$reason; next try in ${delay / 1_000} s")
        return delay.coerceAtMost(POLL_MILLIS)
    }

    companion object {
        const val POLL_MILLIS = 5_000L
        const val MAX_BACKOFF_MILLIS = 60_000L

        /** 5 s, 10 s, 20 s, 40 s, then 60 s. */
        fun backoffMillis(failures: Int): Long =
            (POLL_MILLIS shl (failures - 1).coerceIn(0, 4)).coerceAtMost(MAX_BACKOFF_MILLIS)
    }
}
