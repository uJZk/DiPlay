package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.HotspotAddressBackend
import com.shilapi.xcertplay.network.HotspotAddressVpn
import com.shilapi.xcertplay.network.HotspotExtraAddress
import com.shilapi.xcertplay.network.HotspotExtraAddressKeeper
import com.shilapi.xcertplay.network.RootHotspotAddressBackend
import com.shilapi.xcertplay.network.ShizukuHotspotAddressBackend
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.net.Inet4Address

/**
 * The hotspot address for the car browser (phone + browser mode): the chosen [HotspotAddressMethod], the address, and
 * when the [HotspotExtraAddressKeeper] (Root, Shizuku) or [HotspotAddressVpn] (VPN) runs. Nothing here calls `su`,
 * Shizuku or VpnService directly, or reads interfaces; the keeper and the VPN do that.
 */
internal object HotspotExtraAddressSettings {
    private const val PREFS = "tiplay_hotspot_address"
    /** Before the method chooser: true meant the root method. Read once for the migration, then removed. */
    private const val KEY_ENABLED = "hotspot_extra_address_enabled"
    private const val KEY_METHOD = "hotspot_address_method"
    private const val KEY_ADDRESS = "hotspot_extra_address"

    private val defaultAddress: Inet4Address = checkNotNull(HotspotExtraAddress.parse(HotspotExtraAddress.DEFAULT))

    /** The saved method; Normal by default, so TiPlay never asks for root, Shizuku or a VPN unless chosen. */
    fun method(context: Context): HotspotAddressMethod {
        val prefs = prefs(context)
        prefs.getString(KEY_METHOD, null)?.let { saved ->
            HotspotAddressMethod.entries.firstOrNull { it.name == saved }?.let { return it }
        }
        return if (prefs.getBoolean(KEY_ENABLED, false)) HotspotAddressMethod.ROOT else HotspotAddressMethod.NORMAL
    }

    fun saveMethod(context: Context, method: HotspotAddressMethod) {
        prefs(context).edit().putString(KEY_METHOD, method.name).remove(KEY_ENABLED).apply()
    }

    /** A method other than Normal is chosen: TiPlay provides [address] for the car. */
    fun enabled(context: Context): Boolean = method(context) != HotspotAddressMethod.NORMAL

    /** For callers written before the chooser: on is the root method, off is Normal. */
    fun setEnabled(context: Context, enabled: Boolean) =
        saveMethod(context, if (enabled) HotspotAddressMethod.ROOT else HotspotAddressMethod.NORMAL)

    /** The saved address, or the default when none (or no valid one) is saved. */
    fun address(context: Context): Inet4Address =
        prefs(context).getString(KEY_ADDRESS, null)?.let(HotspotExtraAddress::parse) ?: defaultAddress

    /** The address the chosen method provides for the car's page link, or null with Normal. */
    fun providedAddress(context: Context): Inet4Address? = address(context).takeIf { enabled(context) }

    /** [providedAddress] once it is actually in place: added to the hotspot by the keeper, or held by the VPN. */
    fun liveAddress(context: Context): Inet4Address? {
        val method = method(context)
        val live = when {
            method.usesKeeper -> HotspotExtraAddressKeeper.state is HotspotExtraAddressKeeper.State.Added
            method == HotspotAddressMethod.VPN -> HotspotAddressVpn.state == HotspotAddressVpn.State.Up
            else -> false
        }
        return address(context).takeIf { live }
    }

    fun saveAddress(context: Context, address: Inet4Address) {
        require(HotspotExtraAddress.isAllowed(address)) { "address must be in 100.64.0.0/10 or 169.254.0.0/16" }
        prefs(context).edit().putString(KEY_ADDRESS, address.hostAddress).apply()
    }

    /**
     * The method in effect: the saved one while the run mode in effect is phone + browser, otherwise Normal. A running
     * session keeps the mode it connected with (the run-mode switch applies at the next connection), so leaving phone
     * mode does not cut the car's page off in the middle of a session.
     */
    fun effectiveMethod(context: Context): HotspotAddressMethod =
        if (AirPlayPersistence.isPhoneBrowserMode(context, CarPlayBackgroundSession.snapshot()?.controller)) method(context)
        else HotspotAddressMethod.NORMAL

    /** A method other than Normal is in effect: the keeper or the VPN should run. */
    fun wanted(context: Context): Boolean = effectiveMethod(context) != HotspotAddressMethod.NORMAL

    /** The method the saved settings want once the current session is over. */
    private fun methodAfterSession(context: Context): HotspotAddressMethod =
        if (AirPlayPersistence.isPhoneBrowserMode(context)) method(context) else HotspotAddressMethod.NORMAL

    /**
     * The keeper only touches the phone's own hotspot, never a joined Wi-Fi network (whose link it would disturb). The
     * VPN runs only then too: on a joined network the car reaches the extra address through no method.
     */
    fun manualHotspotLink(context: Context): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.MANUAL

    /**
     * Runs what the method in effect needs and stops the rest: leaving Root removes its address, leaving Shizuku cannot
     * (the address goes when the hotspot restarts), leaving VPN closes the tunnel. Cheap and idempotent; safe from any
     * foreground lifecycle callback. [retry] is only for a tap in the chooser: it retries what stopped the method (root
     * after a successful root check, a ROM that blocked Shizuku, a VPN that was revoked).
     */
    fun sync(context: Context, retry: Boolean = false) {
        val app = context.applicationContext
        val method = effectiveMethod(app)
        if (method.usesKeeper) {
            HotspotExtraAddressKeeper.start(app, address(app),
                eligible = { effectiveMethod(app) == method && manualHotspotLink(app) },
                retryRootDenied = retry, backend = keeperBackend(method))
        } else if (HotspotExtraAddressKeeper.running) {
            HotspotExtraAddressKeeper.stop(removeAddress = true)
        }
        if (vpnWanted(app, method)) {
            HotspotAddressVpn.start(app, address(app), retry)
        } else if (HotspotAddressVpn.requested != null || HotspotAddressVpn.active) {
            HotspotAddressVpn.stop()
        }
    }

    /**
     * The session service ended: stop watching, but keep the address so the car's page still opens. It goes only when
     * the driver left the method or phone + browser mode during the session, which kept it until now.
     */
    fun onSessionServiceStopped(context: Context) {
        val after = methodAfterSession(context.applicationContext)
        val keeperKeeps = after.usesKeeper && HotspotExtraAddressKeeper.backendId == keeperBackendId(after)
        HotspotExtraAddressKeeper.stop(removeAddress = !keeperKeeps)
        if (!vpnWanted(context, after) && (HotspotAddressVpn.requested != null || HotspotAddressVpn.active)) {
            HotspotAddressVpn.stop()
        }
    }

    private fun vpnWanted(context: Context, method: HotspotAddressMethod): Boolean =
        method == HotspotAddressMethod.VPN && manualHotspotLink(context)

    /** For the diagnostic report: method, states and the VPN rule's Android class; never the address. */
    fun diagnosticLine(context: Context): String {
        val keeper = when (val current = HotspotExtraAddressKeeper.state) {
            is HotspotExtraAddressKeeper.State.Added -> "added(${current.iface})"
            is HotspotExtraAddressKeeper.State.Failed -> "failed(${current.reason})"
            HotspotExtraAddressKeeper.State.NoHotspot -> "no-hotspot"
            HotspotExtraAddressKeeper.State.RootDenied -> "root-denied"
            HotspotExtraAddressKeeper.State.Blocked -> "blocked"
            HotspotExtraAddressKeeper.State.ShizukuUnavailable -> "shizuku-not-running"
            HotspotExtraAddressKeeper.State.ShizukuPermissionNeeded -> "shizuku-permission-needed"
            HotspotExtraAddressKeeper.State.Off -> "off"
        }
        val vpn = when (val current = HotspotAddressVpn.state) {
            HotspotAddressVpn.State.Off -> "off"
            HotspotAddressVpn.State.NeedsConsent -> "needs-consent"
            HotspotAddressVpn.State.OtherVpn -> "other-vpn"
            HotspotAddressVpn.State.WiredCarPlayVpn -> "wired-carplay-vpn"
            HotspotAddressVpn.State.Starting -> "starting"
            HotspotAddressVpn.State.Up -> "up"
            HotspotAddressVpn.State.Revoked -> "revoked"
            is HotspotAddressVpn.State.Failed -> "failed(${current.reason})"
        }
        val address = address(context)
        val range = if (HotspotExtraAddress.isLinkLocal(address)) "link-local" else "100.64/10"
        return "Hotspot address: method=${method(context)} effective=${effectiveMethod(context)} " +
            "defaultAddress=${address == defaultAddress} range=$range " +
            "keeper=${HotspotExtraAddressKeeper.backendId ?: "stopped"}:$keeper vpn=$vpn " +
            "vpnRule=${HotspotAddressVpn.androidClass()} vpnProbablyBlocked=${HotspotAddressVpn.probablyBlocked(address)}"
    }

    /** Null is the keeper's root backend. */
    private fun keeperBackend(method: HotspotAddressMethod): HotspotAddressBackend? =
        if (method == HotspotAddressMethod.SHIZUKU) ShizukuHotspotAddressBackend() else null

    private fun keeperBackendId(method: HotspotAddressMethod): String? = when (method) {
        HotspotAddressMethod.ROOT -> RootHotspotAddressBackend.ID
        HotspotAddressMethod.SHIZUKU -> ShizukuHotspotAddressBackend.ID
        else -> null
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
