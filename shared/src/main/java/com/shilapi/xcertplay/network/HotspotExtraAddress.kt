package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * The extra IPv4 address TiPlay can put on this phone's own hotspot, for car browsers (Tesla) that open only
 * addresses in 100.64.0.0/10 (shared address space, RFC 6598). The VPN method may also use 169.254.0.0/16, which
 * Android still accepts from the hotspot on a VPN address. Pure helpers: parsing, validation, the two root scripts and
 * the Shizuku alias. Every value that reaches a script is a validated token; nothing else is ever interpolated.
 */
object HotspotExtraAddress {
    const val DEFAULT = "100.109.220.253"
    /** Suggested for the VPN method where Android drops hotspot traffic to other VPN addresses. */
    const val LINK_LOCAL_SUGGESTION = "169.254.220.253"
    const val ROOT_CHECK_SCRIPT = "id -u"
    /** The label the Shizuku method gives its address (`wlan2:tp`); without one netd first clears the interface. */
    const val ALIAS = "tp"
    private const val IP = "/system/bin/ip"
    private const val MAX_INTERFACE_NAME = 15 // IFNAMSIZ - 1

    // Strict dotted quad, ASCII digits only, no leading zeros: getByName would resolve host names.
    private const val OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])"
    private val DOTTED_QUAD = Regex("$OCTET\\.$OCTET\\.$OCTET\\.$OCTET")
    // IFNAMSIZ - 1 characters, no shell metacharacters, and no leading '-' or '.' that a parser could read as an option.
    private val INTERFACE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,14}")

    /** The address in [text], or null unless it is a plain dotted quad inside 100.64.0.0/10 or 169.254.0.0/16. */
    fun parse(text: String): Inet4Address? {
        if (!DOTTED_QUAD.matches(text)) return null
        val bytes = text.split('.').map { it.toInt().toByte() }.toByteArray()
        val address = InetAddress.getByAddress(bytes) as? Inet4Address ?: return null
        return address.takeIf(::isAllowed)
    }

    /** True for 100.64.0.0/10, the range the car browser reaches. */
    fun isCgnat(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        return (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
    }

    /** 169.254.0.0/16. */
    fun isLinkLocal(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        return (bytes[0].toInt() and 0xff) == 169 && (bytes[1].toInt() and 0xff) == 254
    }

    /** The two ranges TiPlay adds: 100.64.0.0/10 and 169.254.0.0/16. */
    fun isAllowed(address: InetAddress): Boolean = isCgnat(address) || isLinkLocal(address)

    fun isValidInterfaceName(name: String): Boolean = INTERFACE_NAME.matches(name)

    /** `wlan2:tp` for `wlan2`, or null when the name is invalid or the alias would not fit in 15 characters. */
    fun aliasName(iface: String): String? =
        "$iface:$ALIAS".takeIf { isValidInterfaceName(iface) && it.length <= MAX_INTERFACE_NAME }

    /** Idempotent: `replace` succeeds when the address is already there, where `add` fails with "File exists". */
    fun addScript(iface: String, address: Inet4Address): String =
        "$IP -4 addr replace ${token(address)}/32 dev ${token(iface)}"

    fun deleteScript(iface: String, address: Inet4Address): String =
        "$IP -4 addr del ${token(address)}/32 dev ${token(iface)}"

    /** [ROOT_CHECK_SCRIPT] proved root only when it ended normally and printed uid 0. */
    fun rootGranted(result: RootShell.Result): Boolean =
        result is RootShell.Result.Done && result.exitCode == 0 &&
            result.output.lineSequence().map(String::trim).lastOrNull { it.isNotEmpty() } == "0"

    /**
     * Reads the interface without root; false when it is gone. Android lists an address with a label (`wlan2:tp`, the
     * Shizuku method) as its own virtual interface, so the sub-interfaces and the alias count too.
     */
    fun interfaceHasAddress(iface: String, address: Inet4Address): Boolean = runCatching {
        val parent = NetworkInterface.getByName(iface) ?: return@runCatching false
        val alias = aliasName(iface)?.let { name -> runCatching { NetworkInterface.getByName(name) }.getOrNull() }
        (listOf(parent) + Collections.list(parent.subInterfaces) + listOfNotNull(alias)).any { network ->
            Collections.list(network.inetAddresses).any { it is Inet4Address && it.address.contentEquals(address.address) }
        }
    }.getOrDefault(false)

    private fun token(iface: String): String {
        require(isValidInterfaceName(iface)) { "invalid interface name" }
        return iface
    }

    private fun token(address: Inet4Address): String {
        require(isAllowed(address)) { "address must be in 100.64.0.0/10 or 169.254.0.0/16" }
        // Rebuilt from the four bytes, so the text is a canonical dotted quad whatever the object holds.
        return address.address.joinToString(".") { (it.toInt() and 0xff).toString() }
    }
}
