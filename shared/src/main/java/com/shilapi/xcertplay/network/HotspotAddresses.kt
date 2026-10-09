package com.shilapi.xcertplay.network

import android.content.Context
import java.net.Inet4Address
import java.net.InetAddress

/** The phone's own hotspot interface and every IPv4 address on it, including one outside the private ranges. */
object HotspotAddresses {
    data class Current(val iface: String, val ipv4: List<Inet4Address>)

    /** Blocking (enumerates interfaces); never on the main thread. Null when no hotspot interface is up. */
    fun current(context: Context): Current? =
        ManualHotspotInterfaces(context.applicationContext).use { current(it.sample()) }

    /** Picks the interface the way the manual hotspot does, then lists its IPv4 without the site-local filter. */
    internal fun current(snapshot: HotspotNetworkSnapshot): Current? {
        val selected = selectHotspotInterface(snapshot) {} ?: return null
        val iface = snapshot.interfaces.firstOrNull { it.name == selected.name } ?: return null
        val ipv4 = iface.addresses.filterIsInstance<Inet4Address>().filter {
            !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress && !it.isMulticastAddress
        }.distinctBy { it.hostAddress }
        return Current(selected.name, ipv4)
    }
}

/**
 * The manual hotspot's AirPlay and Bonjour addresses. The 100.64.0.0/10 extra address exists only for the car browser:
 * AirPlay never binds to it and Bonjour never advertises it, whatever order the interface lists its addresses in.
 */
internal fun manualHotspotHostAddresses(addresses: List<InetAddress>, interfaceIndex: Int): List<InetAddress> =
    existingWifiHostAddresses(addresses.filterNot(HotspotExtraAddress::isCgnat), interfaceIndex)
