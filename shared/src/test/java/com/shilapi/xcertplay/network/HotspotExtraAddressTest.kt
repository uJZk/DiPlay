package com.shilapi.xcertplay.network

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotExtraAddressTest {
    private val address = HotspotExtraAddress.parse("100.109.220.253")!!

    @Test fun parseAcceptsOnlyPlainDottedQuadsInTheSharedAddressSpace() {
        assertEquals(ip("100.109.220.253"), HotspotExtraAddress.parse(HotspotExtraAddress.DEFAULT))
        assertEquals(ip("100.64.0.0"), HotspotExtraAddress.parse("100.64.0.0"))
        assertEquals(ip("100.127.255.255"), HotspotExtraAddress.parse("100.127.255.255"))
        listOf(
            // Outside 100.64.0.0/10.
            "100.128.0.1", "100.63.255.255", "10.0.0.1", "192.168.43.1", "127.0.0.1", "0.0.0.0", "255.255.255.255",
            // Not a plain dotted quad: getByName would resolve or reinterpret these.
            "1.2.3", "100.109.220", "100.109.220.253.1", "100.109.220.256", "100.064.0.1", "100.109.220.0253",
            "0x64.109.220.253", "100.109.220.253/32", " 100.109.220.253", "100.109.220.253 ", "100.109.220.253\n",
            "localhost", "example.com", "tesla.local", "", ".", "100..220.253", "::ffff:100.109.220.253",
            "١٠٠.١٠٩.٢٢٠.٢٥٣", "100.109.220.253;reboot", "\$(id)",
        ).forEach { assertNull(it, HotspotExtraAddress.parse(it)) }
    }

    @Test fun onlyIpv4InTheSharedAddressSpaceCounts() {
        assertTrue(HotspotExtraAddress.isCgnat(ip("100.64.0.1")))
        assertTrue(HotspotExtraAddress.isCgnat(ip("100.127.0.1")))
        assertFalse(HotspotExtraAddress.isCgnat(ip("100.128.0.1")))
        assertFalse(HotspotExtraAddress.isCgnat(ip("10.176.81.135")))
        assertFalse(HotspotExtraAddress.isCgnat(ip("fe80::1")))
        assertFalse(HotspotExtraAddress.isCgnat(ip("64:ff9b::6464:dcfd")))
    }

    @Test fun scriptsAreExactAndUseOnlyValidatedTokens() {
        assertEquals("/system/bin/ip -4 addr replace 100.109.220.253/32 dev wlan2",
            HotspotExtraAddress.addScript("wlan2", address))
        assertEquals("/system/bin/ip -4 addr del 100.109.220.253/32 dev swlan0",
            HotspotExtraAddress.deleteScript("swlan0", address))
        assertEquals("/system/bin/ip -4 addr replace 100.109.220.253/32 dev p2p-wlan0-0",
            HotspotExtraAddress.addScript("p2p-wlan0-0", address))
        assertEquals("/system/bin/ip -4 addr replace 100.109.220.253/32 dev ap_br.wlan1",
            HotspotExtraAddress.addScript("ap_br.wlan1", address))
        assertEquals("id -u", HotspotExtraAddress.ROOT_CHECK_SCRIPT)
    }

    @Test fun interfaceNamesThatCouldCarryShellSyntaxAreRejected() {
        listOf("wlan2;reboot", "wl an", "abcdefghijklmnop", "", "wlan0\n", "wlan0|sh", "\$(id)", "`id`", "wlan0&",
            "wlan0>x", "../x", "-rf", ".hidden", "wlan'0", "wlan\"0", "wlan0\u0000", "wlan\\0", "wlan:0", "wlаn0")
            .forEach { name ->
                assertFalse(name, HotspotExtraAddress.isValidInterfaceName(name))
                assertThrows(name, IllegalArgumentException::class.java) { HotspotExtraAddress.addScript(name, address) }
                assertThrows(name, IllegalArgumentException::class.java) { HotspotExtraAddress.deleteScript(name, address) }
            }
        assertTrue(HotspotExtraAddress.isValidInterfaceName("abcdefghijklmno"))
    }

    @Test fun scriptsRefuseAnAddressOutsideTheSharedAddressSpace() {
        val private = ip("10.176.81.135") as Inet4Address
        assertThrows(IllegalArgumentException::class.java) { HotspotExtraAddress.addScript("wlan2", private) }
        assertThrows(IllegalArgumentException::class.java) { HotspotExtraAddress.deleteScript("wlan2", private) }
    }

    @Test fun onlyUidZeroFromACompletedCheckIsRoot() {
        assertTrue(HotspotExtraAddress.rootGranted(RootShell.Result.Done(0, "0")))
        assertTrue(HotspotExtraAddress.rootGranted(RootShell.Result.Done(0, "Magisk granted\n0\n")))
        assertFalse(HotspotExtraAddress.rootGranted(RootShell.Result.Done(0, "2000")))
        assertFalse(HotspotExtraAddress.rootGranted(RootShell.Result.Done(1, "0")))
        assertFalse(HotspotExtraAddress.rootGranted(RootShell.Result.Done(0, "")))
        assertFalse(HotspotExtraAddress.rootGranted(RootShell.Result.Unavailable))
        assertFalse(HotspotExtraAddress.rootGranted(RootShell.Result.TimedOut))
    }

    @Test fun currentHotspotListsEveryIpv4OfTheApInterface() {
        val snapshot = HotspotNetworkSnapshot(
            interfaces = listOf(
                HotspotInterfaceSnapshot("rmnet_data0", 9, true, listOf(ip("100.70.1.2")), false),
                HotspotInterfaceSnapshot("wlan2", 7, true,
                    listOf(ip("fe80::1"), ip("100.109.220.253"), ip("10.176.81.135"), ip("169.254.3.4")), true),
            ),
            apInterfaces = setOf("wlan2"),
            wifiUpstreams = emptySet(),
            defaultInterface = "rmnet_data0",
        )

        val current = HotspotAddresses.current(snapshot)

        assertNotNull(current)
        assertEquals("wlan2", current!!.iface)
        assertEquals(listOf(ip("100.109.220.253"), ip("10.176.81.135")), current.ipv4)
    }

    @Test fun noHotspotWhenTheApIsOffOrOnlyTheMobileUplinkHasAnAddress() {
        val wlan = HotspotInterfaceSnapshot("wlan2", 7, true, listOf(ip("10.176.81.135")), true)
        assertNull(HotspotAddresses.current(HotspotNetworkSnapshot(listOf(wlan), setOf("wlan2"), emptySet(), null,
            apEnabled = false)))
        assertNull(HotspotAddresses.current(HotspotNetworkSnapshot(
            listOf(HotspotInterfaceSnapshot("rmnet_data0", 9, true, listOf(ip("100.70.1.2")), false)),
            emptySet(), emptySet(), "rmnet_data0")))
    }

    private fun ip(value: String): InetAddress = InetAddress.getByName(value)
}
