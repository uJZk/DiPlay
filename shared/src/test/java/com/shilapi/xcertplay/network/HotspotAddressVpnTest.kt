package com.shilapi.xcertplay.network

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.shilapi.xcertplay.network.HotspotAddressVpn.Conflict
import com.shilapi.xcertplay.network.HotspotAddressVpn.Owner
import com.shilapi.xcertplay.network.HotspotAddressVpn.State
import com.shilapi.xcertplay.network.HotspotAddressVpn.VpnNetwork
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HotspotAddressVpnTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val cgnat = ip("100.109.220.253")
    private val linkLocal = ip("169.254.220.253")
    private val calls = CopyOnWriteArrayList<String>()
    private val started = CopyOnWriteArrayList<Intent>()
    private val tunnels = CopyOnWriteArrayList<ParcelFileDescriptor>()
    @Volatile private var prepared = true
    @Volatile private var networks = emptyList<VpnNetwork>()
    @Volatile private var establishes = true

    private inner class RecordingBuilder : HotspotAddressVpn.TunnelBuilder {
        override fun addAddress(address: Inet4Address, prefixLength: Int) = apply { calls += "addAddress ${address.hostAddress}/$prefixLength" }
        override fun addAllowedApplication(packageName: String) = apply { calls += "addAllowedApplication $packageName" }
        override fun allowFamily(family: Int) = apply { calls += "allowFamily ${if (family == OsConstants.AF_INET6) "AF_INET6" else family}" }
        override fun setBlocking(blocking: Boolean) = apply { calls += "setBlocking $blocking" }
        override fun setMetered(metered: Boolean) = apply { calls += "setMetered $metered" }
        override fun setSession(session: String) = apply { calls += "setSession $session" }
        override fun establish(): ParcelFileDescriptor? {
            calls += "establish"
            if (!establishes) return null
            val file = File.createTempFile("tun", null, context.cacheDir)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).also { tunnels += it }
        }
    }

    @Before fun setUp() {
        HotspotAddressVpn.dependencies = HotspotAddressVpn.Dependencies(
            prepared = { prepared },
            networks = { networks },
            builder = { RecordingBuilder() },
            startService = { _, intent -> started += intent },
            log = {},
        )
        HotspotAddressVpn.stop()
    }

    @After fun tearDown() {
        HotspotAddressVpn.stop()
        HotspotAddressVpn.dependencies = HotspotAddressVpn.Dependencies()
        HotspotAddressVpn.service = null
    }

    @Test fun theTunnelHoldsOnlyTheAddressForThisAppAndLetsIpv6Through() {
        HotspotAddressVpn.configure(RecordingBuilder(), cgnat, "com.ujzk.tiplay")

        // No addRoute, no DNS: the builder port cannot even express them.
        assertEquals(listOf(
            "addAddress 100.109.220.253/32",
            "addAllowedApplication com.ujzk.tiplay",
            "allowFamily AF_INET6",
            "setBlocking false",
            "setMetered false",
            "setSession TiPlay hotspot address",
        ), calls)
    }

    @Test fun theVpnProbablyFailsWhereAndroidDropsHotspotTrafficToVpnAddresses() {
        // Android 15 and later.
        assertTrue(HotspotAddressVpn.probablyBlocked(35, "2024-10-05", cgnat))
        assertTrue(HotspotAddressVpn.probablyBlocked(37, null, cgnat))
        // Android 14 from the January 2025 patch; an unreadable patch counts as patched.
        assertTrue(HotspotAddressVpn.probablyBlocked(34, "2025-01-01", cgnat))
        assertTrue(HotspotAddressVpn.probablyBlocked(34, "2026-07-05", cgnat))
        assertTrue(HotspotAddressVpn.probablyBlocked(34, "", cgnat))
        assertTrue(HotspotAddressVpn.probablyBlocked(34, null, cgnat))
        assertTrue(HotspotAddressVpn.probablyBlocked(34, "January 2025", cgnat))
        assertFalse(HotspotAddressVpn.probablyBlocked(34, "2024-12-05", cgnat))
        assertFalse(HotspotAddressVpn.probablyBlocked(33, "2026-07-01", cgnat))
        assertFalse(HotspotAddressVpn.probablyBlocked(28, null, cgnat))
        // Link-local addresses are exempt everywhere.
        listOf(28, 34, 35, 37).forEach { assertFalse(HotspotAddressVpn.probablyBlocked(it, "2026-07-01", linkLocal)) }

        assertEquals("android15+", HotspotAddressVpn.androidClass(36, "2026-07-01"))
        assertEquals("android14-patched", HotspotAddressVpn.androidClass(34, "2025-02-01"))
        assertEquals("android14-unpatched", HotspotAddressVpn.androidClass(34, "2024-11-01"))
        assertEquals("before-android14", HotspotAddressVpn.androidClass(30, "2024-11-01"))
    }

    @Test fun anotherVpnOrTheWiredCarPlayVpnIsAConflict() {
        assertEquals(Conflict.NONE, HotspotAddressVpn.conflictOf(emptyList()))
        assertEquals(Conflict.OTHER_VPN, HotspotAddressVpn.conflictOf(listOf(VpnNetwork(Owner.OTHER_APP, emptyList()))))
        // Another app with a 100.64 address (Tailscale) is still another app.
        assertEquals(Conflict.OTHER_VPN, HotspotAddressVpn.conflictOf(listOf(VpnNetwork(Owner.OTHER_APP, listOf(ip("100.101.1.2"))))))
        // The wired CarPlay tunnel has only an IPv6 link-local address.
        assertEquals(Conflict.WIRED_CARPLAY, HotspotAddressVpn.conflictOf(listOf(VpnNetwork(Owner.THIS_APP, emptyList()))))
        assertEquals(Conflict.NONE, HotspotAddressVpn.conflictOf(listOf(VpnNetwork(Owner.THIS_APP, listOf(cgnat)))))
        // Android 9 hides owners: any VPN counts as another app's unless the hotspot tunnel is up.
        assertEquals(Conflict.OTHER_VPN, HotspotAddressVpn.conflictOf(listOf(VpnNetwork(Owner.UNKNOWN, listOf(cgnat)))))
    }

    @Test fun startWithoutConsentOnlyReportsIt() {
        prepared = false

        HotspotAddressVpn.start(context, cgnat)

        assertEquals(State.NeedsConsent, HotspotAddressVpn.state)
        assertTrue(started.isEmpty())
        assertTrue(calls.isEmpty())
    }

    @Test fun startNeverAsksForConsentWhileAnotherVpnRuns() {
        prepared = false
        networks = listOf(VpnNetwork(Owner.OTHER_APP, emptyList()))
        HotspotAddressVpn.start(context, cgnat)
        assertEquals(State.OtherVpn, HotspotAddressVpn.state)

        prepared = true
        networks = listOf(VpnNetwork(Owner.THIS_APP, emptyList()))
        HotspotAddressVpn.start(context, cgnat)
        assertEquals(State.WiredCarPlayVpn, HotspotAddressVpn.state)

        assertTrue(started.isEmpty())
    }

    @Test fun theServiceHoldsTheAddressAndStopClosesIt() {
        val service = startService(cgnat)

        assertEquals(State.Up, HotspotAddressVpn.state)
        assertSame(service.get(), HotspotAddressVpn.service)
        assertEquals(cgnat, service.get().heldAddress)
        assertEquals("establish", calls.last())
        assertTrue(tunnels.single().fileDescriptor.valid())

        HotspotAddressVpn.stop()

        assertEquals(State.Off, HotspotAddressVpn.state)
        assertFalse(tunnels.single().fileDescriptor.valid())
        assertNull(service.get().heldAddress)
        assertNull(HotspotAddressVpn.requested)
    }

    @Test fun aNewAddressReplacesTheTunnelThenClosesTheOldOne() {
        val service = startService(cgnat)

        HotspotAddressVpn.start(context, linkLocal)
        service.withIntent(started.last()).startCommand(0, 2)

        assertEquals(2, tunnels.size)
        assertFalse(tunnels[0].fileDescriptor.valid())
        assertTrue(tunnels[1].fileDescriptor.valid())
        assertEquals(linkLocal, service.get().heldAddress)
        assertEquals(State.Up, HotspotAddressVpn.state)
        // The same address again changes nothing.
        HotspotAddressVpn.start(context, linkLocal)
        assertEquals(2, started.size)
    }

    @Test fun theServiceGivesWayInsteadOfFighting() {
        HotspotAddressVpn.start(context, cgnat)
        networks = listOf(VpnNetwork(Owner.OTHER_APP, emptyList())) // started in between
        Robolectric.buildService(HotspotAddressVpnService::class.java, started.single()).create().startCommand(0, 1)
        assertEquals(State.OtherVpn, HotspotAddressVpn.state)
        assertTrue("establish" !in calls)

        networks = emptyList()
        prepared = false // consent revoked in between
        HotspotAddressVpn.start(context, cgnat)
        assertEquals(State.NeedsConsent, HotspotAddressVpn.state)
        assertTrue("establish" !in calls)
    }

    @Test fun revokingClosesTheTunnelAndNothingRestartsIt() {
        val service = startService(cgnat)

        service.get().onRevoke()

        assertEquals(State.Revoked, HotspotAddressVpn.state)
        assertFalse(tunnels.single().fileDescriptor.valid())
        assertEquals(1, started.size)

        // An activity resume, a new connection or a new address does not undo the driver's (or the other app's) choice.
        HotspotAddressVpn.start(context, cgnat)
        HotspotAddressVpn.start(context, linkLocal)
        assertEquals(State.Revoked, HotspotAddressVpn.state)
        assertEquals(1, started.size)

        // Only a tap in the settings starts it again.
        HotspotAddressVpn.start(context, linkLocal, retry = true)
        assertEquals(State.Starting, HotspotAddressVpn.state)
        assertEquals(2, started.size)
    }

    @Test fun aTapAfterARevokeStillNeverReplacesAnotherVpn() {
        startService(cgnat).get().onRevoke()
        networks = listOf(VpnNetwork(Owner.OTHER_APP, emptyList())) // the app that took over

        HotspotAddressVpn.start(context, cgnat, retry = true)

        assertEquals(State.OtherVpn, HotspotAddressVpn.state)
        assertEquals(1, started.size)
    }

    @Test fun leavingTheMethodForgetsARevoke() {
        startService(cgnat).get().onRevoke()

        HotspotAddressVpn.stop()
        HotspotAddressVpn.start(context, cgnat)

        assertEquals(State.Starting, HotspotAddressVpn.state)
        assertEquals(2, started.size)
    }

    @Test fun theWiredCarPlayVpnTakingTheSlotClosesThisTunnel() {
        val service = startService(cgnat)
        networks = listOf(VpnNetwork(Owner.THIS_APP, emptyList()))

        service.get().onUnbind(Intent(VpnService.SERVICE_INTERFACE))

        assertEquals(State.WiredCarPlayVpn, HotspotAddressVpn.state)
        assertFalse(tunnels.single().fileDescriptor.valid())
    }

    @Test fun aLostBindingBeforeTheWiredTunnelIsVisibleIsStillTheWiredVpnNotARevoke() {
        // Android drops the binding right after the wired tunnel takes the slot; its addresses may not be published yet.
        val service = startService(cgnat)
        networks = listOf(VpnNetwork(Owner.THIS_APP, listOf(cgnat)))

        service.get().onUnbind(Intent(VpnService.SERVICE_INTERFACE))

        assertEquals(State.WiredCarPlayVpn, HotspotAddressVpn.state)
        assertFalse(tunnels.single().fileDescriptor.valid())
        // Once the wired connection is over, the next resume or connection starts the tunnel again.
        networks = emptyList()
        HotspotAddressVpn.start(context, cgnat)
        assertEquals(State.Starting, HotspotAddressVpn.state)
    }

    @Test fun aRefusedEstablishMeansConsentIsGone() {
        establishes = false

        startService(cgnat)

        assertEquals(State.NeedsConsent, HotspotAddressVpn.state)
    }

    @Test fun aStartAfterStopDoesNotReopenTheTunnel() {
        HotspotAddressVpn.start(context, cgnat)
        val intent = started.single()
        HotspotAddressVpn.stop()

        Robolectric.buildService(HotspotAddressVpnService::class.java, intent).create().startCommand(0, 1)

        assertEquals(State.Off, HotspotAddressVpn.state)
        assertTrue("establish" !in calls)
    }

    private fun startService(address: Inet4Address): ServiceController<HotspotAddressVpnService> {
        HotspotAddressVpn.start(context, address)
        assertEquals(State.Starting, HotspotAddressVpn.state)
        val intent = started.last()
        assertEquals(HotspotAddressVpnService.ACTION_START, intent.action)
        assertEquals(address.hostAddress, intent.getStringExtra(HotspotAddressVpnService.EXTRA_ADDRESS))
        return Robolectric.buildService(HotspotAddressVpnService::class.java, intent).create().startCommand(0, 1)
    }

    private fun ip(value: String) = InetAddress.getByName(value) as Inet4Address
}
