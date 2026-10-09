package com.shilapi.xcertplay.network

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.net.Inet4Address

/**
 * Holds the hotspot address on a VPN tunnel for the VPN method; see [HotspotAddressVpn]. Separate from the wired
 * path's [CarPlayVpnService]. It never calls startForeground, so it needs no foreground service type on Android 14+:
 * while the tunnel is up, Android binds the service itself (BIND_FOREGROUND_SERVICE) and shows its own VPN icon.
 * Main thread only.
 */
class HotspotAddressVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null

    /** The address the tunnel holds, or null. */
    var heldAddress: Inet4Address? = null
        private set

    override fun onCreate() {
        super.onCreate()
        HotspotAddressVpn.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.takeIf { it.action == ACTION_START }?.getStringExtra(EXTRA_ADDRESS)
            ?.let(HotspotExtraAddress::parse)
        if (address == null || HotspotAddressVpn.requested == null) release(HotspotAddressVpn.State.Off) else hold(address)
        return START_NOT_STICKY
    }

    /** Establishes the tunnel for [address]; a running tunnel for another address is replaced, then closed. */
    internal fun hold(address: Inet4Address) {
        val dependencies = HotspotAddressVpn.dependencies
        if (tunnel != null && heldAddress == address) return HotspotAddressVpn.publish(HotspotAddressVpn.State.Up)
        // Checked again here: another VPN can start, and consent can be revoked, after HotspotAddressVpn.start.
        when (HotspotAddressVpn.conflict(this)) {
            HotspotAddressVpn.Conflict.OTHER_VPN -> return release(HotspotAddressVpn.State.OtherVpn)
            HotspotAddressVpn.Conflict.WIRED_CARPLAY -> return release(HotspotAddressVpn.State.WiredCarPlayVpn)
            HotspotAddressVpn.Conflict.NONE -> Unit
        }
        if (!runCatching { dependencies.prepared(this) }.getOrDefault(false)) return release(HotspotAddressVpn.State.NeedsConsent)
        val established = try {
            HotspotAddressVpn.configure(dependencies.builder(this), address, packageName).establish()
        } catch (error: Exception) {
            dependencies.log("VPN not established: ${error.javaClass.simpleName}")
            return release(HotspotAddressVpn.State.Failed(error.javaClass.simpleName))
        } ?: return release(HotspotAddressVpn.State.NeedsConsent) // consent was revoked in between
        val previous = tunnel
        tunnel = established
        heldAddress = address
        // The new interface replaced the old one; closing its descriptor now only frees it.
        runCatching { previous?.close() }
        dependencies.log("VPN up")
        HotspotAddressVpn.publish(HotspotAddressVpn.State.Up)
    }

    /** Closes the tunnel, reports [next] and lets the service end. */
    internal fun release(next: HotspotAddressVpn.State) {
        runCatching { tunnel?.close() }
        tunnel = null
        heldAddress = null
        HotspotAddressVpn.publish(next)
        stopSelf()
    }

    /** The driver turned the VPN off in Android settings, or another VPN app was allowed: never fight it. */
    override fun onRevoke() {
        HotspotAddressVpn.dependencies.log("VPN revoked")
        release(HotspotAddressVpn.State.Revoked)
    }

    /**
     * Android drops its binding when the app's VPN slot gets a new tunnel. While this one is still open that means the
     * wired CarPlay path took the slot (the same package has one VPN), or the system closed it: give way.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        if (intent?.action == SERVICE_INTERFACE && tunnel != null) {
            val next = when (HotspotAddressVpn.conflict(this)) {
                HotspotAddressVpn.Conflict.WIRED_CARPLAY -> HotspotAddressVpn.State.WiredCarPlayVpn
                HotspotAddressVpn.Conflict.OTHER_VPN -> HotspotAddressVpn.State.OtherVpn
                HotspotAddressVpn.Conflict.NONE -> HotspotAddressVpn.State.Revoked
            }
            HotspotAddressVpn.dependencies.log("VPN binding dropped: $next")
            release(next)
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (tunnel != null) release(HotspotAddressVpn.State.Off)
        if (HotspotAddressVpn.service === this) HotspotAddressVpn.service = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.ujzk.tiplay.HOTSPOT_ADDRESS_VPN_START"
        const val EXTRA_ADDRESS = "address"
    }
}
