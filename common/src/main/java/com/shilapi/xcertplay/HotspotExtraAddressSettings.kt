package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.HotspotExtraAddress
import com.shilapi.xcertplay.network.HotspotExtraAddressKeeper
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.net.Inet4Address

/**
 * The root-only extra hotspot address for the car browser (phone + browser mode): its two settings and when the
 * [HotspotExtraAddressKeeper] runs. Nothing here calls `su` or reads interfaces; the keeper does that on its own thread.
 */
internal object HotspotExtraAddressSettings {
    private const val PREFS = "tiplay_hotspot_address"
    private const val KEY_ENABLED = "hotspot_extra_address_enabled"
    private const val KEY_ADDRESS = "hotspot_extra_address"

    private val defaultAddress: Inet4Address = checkNotNull(HotspotExtraAddress.parse(HotspotExtraAddress.DEFAULT))

    /** Off by default: without the setting TiPlay never asks for root. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** The saved address, or the default when none (or no valid one) is saved. */
    fun address(context: Context): Inet4Address =
        prefs(context).getString(KEY_ADDRESS, null)?.let(HotspotExtraAddress::parse) ?: defaultAddress

    fun saveAddress(context: Context, address: Inet4Address) {
        require(HotspotExtraAddress.isCgnat(address)) { "address must be in 100.64.0.0/10" }
        prefs(context).edit().putString(KEY_ADDRESS, address.hostAddress).apply()
    }

    /** Phone + browser mode with the setting on: the keeper should run. */
    fun wanted(context: Context): Boolean =
        AirPlayPersistence.isPhoneBrowserMode(context) && enabled(context)

    /** The keeper only touches the phone's own hotspot, never a joined Wi-Fi network (whose link it would disturb). */
    fun manualHotspotLink(context: Context): Boolean =
        AirPlayPersistence.loadWirelessHotspotMode(context) == WirelessHotspotMode.MANUAL

    /**
     * Starts the keeper when it is [wanted], otherwise stops it and removes the address (the driver turned the run mode
     * or the setting off). Cheap and idempotent; safe from any lifecycle callback. [retryRootDenied] is for the settings
     * toggle right after a successful root check.
     */
    fun sync(context: Context, retryRootDenied: Boolean = false) {
        val app = context.applicationContext
        if (wanted(app)) {
            HotspotExtraAddressKeeper.start(app, address(app),
                eligible = { wanted(app) && manualHotspotLink(app) }, retryRootDenied = retryRootDenied)
        } else if (HotspotExtraAddressKeeper.running) {
            HotspotExtraAddressKeeper.stop(removeAddress = true)
        }
    }

    /** The session service ended: stop watching, but keep the address so the car's page still opens. */
    fun onSessionServiceStopped() {
        HotspotExtraAddressKeeper.stop(removeAddress = false)
    }

    /** For the diagnostic report; no address. */
    fun diagnosticLine(context: Context): String {
        val state = when (val current = HotspotExtraAddressKeeper.state) {
            is HotspotExtraAddressKeeper.State.Added -> "added(${current.iface})"
            is HotspotExtraAddressKeeper.State.Failed -> "failed(${current.reason})"
            HotspotExtraAddressKeeper.State.NoHotspot -> "no-hotspot"
            HotspotExtraAddressKeeper.State.RootDenied -> "root-denied"
            HotspotExtraAddressKeeper.State.Off -> "off"
        }
        return "Hotspot extra address: enabled=${enabled(context)} defaultAddress=${address(context) == defaultAddress} " +
            "running=${HotspotExtraAddressKeeper.running} state=$state"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
