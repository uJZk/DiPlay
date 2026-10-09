package com.shilapi.xcertplay.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The VPN method (experimental): a minimal [HotspotAddressVpnService] holds the extra address on its own tunnel, so no
 * root and no Shizuku are needed. The tunnel has the address and nothing else: no routes, only TiPlay as allowed app
 * (no other app's traffic is affected), IPv6 allowed so TiPlay's IPv6 is not black-holed, and its file descriptor is
 * never read or written. TiPlay's own IPv4 finds no route there and falls through to the hotspot and the default
 * network as before.
 *
 * Android 14 with the January 2025 update and Android 15 and later drop packets that arrive on another interface for a
 * VPN address (CVE-2024-49734); 169.254.0.0/16 is exempt. [probablyBlocked] says when the method most likely fails.
 *
 * Consent comes only from a tap in the settings ([consentIntent]). TiPlay never replaces another app's VPN and never
 * starts while its own wired CarPlay VPN ([CarPlayVpnService]) holds the package's single VPN slot.
 */
object HotspotAddressVpn {
    sealed interface State {
        data object Off : State
        /** Android has not allowed TiPlay's VPN; only a tap in the settings asks. */
        data object NeedsConsent : State
        /** Another app's VPN is on; TiPlay does not replace it. */
        data object OtherVpn : State
        /** TiPlay's wired CarPlay connection uses the app's VPN slot. */
        data object WiredCarPlayVpn : State
        data object Starting : State
        data object Up : State
        /** The driver turned the VPN off in Android settings, or another VPN app took over; only a tap starts it again. */
        data object Revoked : State
        data class Failed(val reason: String) : State
    }

    enum class Conflict { NONE, OTHER_VPN, WIRED_CARPLAY }

    /** Who runs a VPN network: Android shows the owner only to the owner, and only from Android 10. */
    internal enum class Owner { THIS_APP, OTHER_APP, UNKNOWN }

    /** One VPN network as this app sees it. */
    internal data class VpnNetwork(val owner: Owner, val ipv4: List<Inet4Address>)

    /** The calls [HotspotAddressVpnService] makes on its `VpnService.Builder`; tests record them instead. */
    internal interface TunnelBuilder {
        fun addAddress(address: Inet4Address, prefixLength: Int): TunnelBuilder
        fun addAllowedApplication(packageName: String): TunnelBuilder
        fun allowFamily(family: Int): TunnelBuilder
        fun setBlocking(blocking: Boolean): TunnelBuilder
        fun setMetered(metered: Boolean): TunnelBuilder
        fun setSession(session: String): TunnelBuilder
        fun establish(): ParcelFileDescriptor?
    }

    internal class Dependencies(
        /** True when Android already allowed this app's VPN (VpnService.prepare returned null). */
        val prepared: (Context) -> Boolean = { context -> VpnService.prepare(context) == null },
        val networks: (Context) -> List<VpnNetwork> = ::vpnNetworks,
        val builder: (VpnService) -> TunnelBuilder = ::PlatformTunnelBuilder,
        val startService: (Context, Intent) -> Unit = { context, intent -> context.startService(intent) },
        val log: (String) -> Unit = { message -> runCatching { Log.i(TAG, message) } },
    )

    const val SESSION_NAME = "TiPlay hotspot address"
    private const val TAG = "TiPlay-HotspotVpn"
    private const val PREFIX_LENGTH = 32

    @Volatile var state: State = State.Off
        private set

    /** The address TiPlay asked the tunnel to hold, or null after [stop]. */
    @Volatile var requested: Inet4Address? = null
        private set

    @Volatile internal var dependencies = Dependencies()
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    // Main thread only: the running service, which holds the tunnel.
    internal var service: HotspotAddressVpnService? = null

    /**
     * Holds [address] on the tunnel, or moves the tunnel to it. Without consent it only reports [State.NeedsConsent]:
     * the consent dialog opens only from a tap. Call from the foreground (an activity or the session service).
     *
     * After [State.Revoked] (the driver switched the VPN off in Android settings, or another VPN app took over) only a
     * tap in the settings ([retry]) starts it again: an activity resume or a new connection must not undo that, and
     * `VpnService.prepare` would switch off the other app's VPN while it is still connecting.
     */
    fun start(context: Context, address: Inet4Address, retry: Boolean = false) {
        require(HotspotExtraAddress.isAllowed(address)) { "address must be in 100.64.0.0/10 or 169.254.0.0/16" }
        val app = context.applicationContext
        requested = address
        if (state == State.Revoked && !retry) return
        if (state == State.Up && service?.heldAddress == address) return
        // Conflicts first: consent for TiPlay would switch another app's VPN off.
        when (conflict(app)) {
            Conflict.OTHER_VPN -> return publish(State.OtherVpn)
            Conflict.WIRED_CARPLAY -> return publish(State.WiredCarPlayVpn)
            Conflict.NONE -> Unit
        }
        if (!runCatching { dependencies.prepared(app) }.getOrDefault(false)) {
            publish(State.NeedsConsent)
            return
        }
        if (state != State.Up) publish(State.Starting)
        runCatching {
            dependencies.startService(app, Intent(app, HotspotAddressVpnService::class.java)
                .setAction(HotspotAddressVpnService.ACTION_START)
                .putExtra(HotspotAddressVpnService.EXTRA_ADDRESS, address.hostAddress))
        }.onFailure {
            dependencies.log("could not start the VPN service: ${it.javaClass.simpleName}")
            publish(State.Failed("service not started"))
        }
    }

    /** Closes the tunnel when one is up; the driver left the method or the mode. Safe from any thread. */
    fun stop() {
        requested = null
        val close = Runnable {
            service?.release(State.Off) ?: publish(State.Off)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) close.run() else main.post(close)
    }

    /** TiPlay holds or is about to hold a tunnel. */
    val active: Boolean get() = state == State.Up || state == State.Starting

    /** The consent dialog's intent, or null when Android already allowed the VPN. Only for a tap in the settings. */
    fun consentIntent(context: Context): Intent? = runCatching { VpnService.prepare(context) }.getOrNull()

    /** What stands in the way of the tunnel right now. Binder calls; never while building the search index. */
    fun conflict(context: Context): Conflict =
        conflictOf(runCatching { dependencies.networks(context.applicationContext) }.getOrDefault(emptyList()))

    /**
     * Another app's VPN, or this app's own VPN without a TiPlay hotspot address (the wired CarPlay tunnel, which has
     * only an IPv6 link-local address). Android 9 hides VPN owners, so there any VPN counts as another app's unless
     * TiPlay's hotspot tunnel is up.
     */
    internal fun conflictOf(networks: List<VpnNetwork>): Conflict = when {
        networks.any { it.owner == Owner.OTHER_APP } -> Conflict.OTHER_VPN
        networks.any { it.owner == Owner.THIS_APP && it.ipv4.none(HotspotExtraAddress::isAllowed) } -> Conflict.WIRED_CARPLAY
        networks.any { it.owner == Owner.UNKNOWN } && state != State.Up -> Conflict.OTHER_VPN
        else -> Conflict.NONE
    }

    /**
     * Android 15 and later, and Android 14 from the 2025-01-01 security patch, drop hotspot packets for a VPN address
     * unless it is link-local (CVE-2024-49734). An unreadable patch level on Android 14 counts as patched.
     */
    fun probablyBlocked(sdk: Int, securityPatch: String?, address: Inet4Address): Boolean =
        !HotspotExtraAddress.isLinkLocal(address) && dropsVpnAddressTraffic(sdk, securityPatch)

    fun probablyBlocked(address: Inet4Address): Boolean =
        probablyBlocked(Build.VERSION.SDK_INT, Build.VERSION.SECURITY_PATCH, address)

    internal fun dropsVpnAddressTraffic(sdk: Int, securityPatch: String?): Boolean = when {
        sdk >= 35 -> true
        sdk == 34 -> securityPatch?.trim()?.takeIf { PATCH.matches(it) }?.let { it >= FIRST_PATCHED } ?: true
        else -> false
    }

    /** For the diagnostic report: which side of the CVE-2024-49734 rule this phone is on. */
    fun androidClass(sdk: Int = Build.VERSION.SDK_INT, securityPatch: String? = Build.VERSION.SECURITY_PATCH): String = when {
        sdk >= 35 -> "android15+"
        sdk == 34 -> if (dropsVpnAddressTraffic(sdk, securityPatch)) "android14-patched" else "android14-unpatched"
        else -> "before-android14"
    }

    /** The tunnel's parameters, in order; [HotspotAddressVpnService] calls [TunnelBuilder.establish] afterwards. */
    internal fun configure(builder: TunnelBuilder, address: Inet4Address, packageName: String): TunnelBuilder =
        builder.addAddress(address, PREFIX_LENGTH)
            // An empty list would route every app through the tunnel.
            .addAllowedApplication(packageName)
            .allowFamily(OsConstants.AF_INET6)
            .setBlocking(false)
            // Metered only when the network underneath is.
            .setMetered(false)
            .setSession(SESSION_NAME)

    /** [listener] runs on the thread that changed the state. */
    fun addListener(listener: (State) -> Unit) { listeners += listener }

    fun removeListener(listener: (State) -> Unit) { listeners -= listener }

    internal fun publish(next: State) {
        if (state == next) return
        state = next
        listeners.forEach { runCatching { it(next) } }
    }

    private val PATCH = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private const val FIRST_PATCHED = "2025-01-01"

    private fun vpnNetworks(context: Context): List<VpnNetwork> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        @Suppress("DEPRECATION")
        return connectivity.allNetworks.mapNotNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            // Android shows a VPN's owner only to that owner (API 29+); others read INVALID_UID.
            val owner = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> Owner.UNKNOWN
                caps.ownerUid == Process.myUid() -> Owner.THIS_APP
                else -> Owner.OTHER_APP
            }
            val ipv4 = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
                .map { it.address }.filterIsInstance<Inet4Address>()
            VpnNetwork(owner, ipv4)
        }
    }

    private class PlatformTunnelBuilder(service: VpnService) : TunnelBuilder {
        private val builder = service.Builder()
        override fun addAddress(address: Inet4Address, prefixLength: Int) = apply { builder.addAddress(address, prefixLength) }
        override fun addAllowedApplication(packageName: String) = apply { builder.addAllowedApplication(packageName) }
        override fun allowFamily(family: Int) = apply { builder.allowFamily(family) }
        override fun setBlocking(blocking: Boolean) = apply { builder.setBlocking(blocking) }
        override fun setMetered(metered: Boolean) = apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(metered)
        }
        override fun setSession(session: String) = apply { builder.setSession(session) }
        override fun establish(): ParcelFileDescriptor? = builder.establish()
    }
}
