package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R

/**
 * How TiPlay gives the car browser an address it can open (phone + browser mode). Persisted by name; see
 * [HotspotExtraAddressSettings]. The order is the chooser's.
 */
internal enum class HotspotAddressMethod(val label: Int, val description: Int) {
    /** Adds nothing: for other car browsers, or a hotspot that already uses 100.64.0.0/10. */
    NORMAL(R.string.settings_hotspot_method_normal, R.string.settings_hotspot_method_normal_description),
    /** `ip addr` as root; removed again when the driver leaves the method. */
    ROOT(R.string.settings_hotspot_method_root, R.string.settings_hotspot_method_root_description),
    /** A VPN tunnel that only holds the address (experimental). */
    VPN(R.string.settings_hotspot_method_vpn, R.string.settings_hotspot_method_vpn_description),
    /** network_management through Shizuku, without root; cannot remove the address. */
    SHIZUKU(R.string.settings_hotspot_method_shizuku, R.string.settings_hotspot_method_shizuku_description),
    ;

    /** The keeper adds and re-adds the address on the hotspot interface. */
    val usesKeeper: Boolean get() = this == ROOT || this == SHIZUKU
}
