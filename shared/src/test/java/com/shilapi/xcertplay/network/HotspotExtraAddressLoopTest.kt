package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.network.HotspotExtraAddressKeeper.State
import java.net.Inet4Address
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotExtraAddressLoopTest {
    private val address = HotspotExtraAddress.parse("100.109.220.253")!!

    /** A phone whose hotspot interface and addresses the test controls; `ip` changes them like the kernel would. */
    private inner class Phone(rootDenied: Boolean = false) {
        var now = 1_000L
        var iface: String? = "wlan2"
        var eligible = true
        val addresses = mutableMapOf<String, MutableSet<Inet4Address>>()
        val scripts = mutableListOf<String>()
        val shellTimes = mutableListOf<Long>()
        var finds = 0
        var answer: (String) -> RootShell.Result = { script ->
            Regex("^/system/bin/ip -4 addr (replace|del) ([0-9.]+)/32 dev (\\S+)$").matchEntire(script)?.let {
                val set = addresses.getOrPut(it.groupValues[3]) { mutableSetOf() }
                if (it.groupValues[1] == "replace") set += address else set -= address
                RootShell.Result.Done(0, "")
            } ?: RootShell.Result.Done(1, "unexpected")
        }

        val loop = HotspotExtraAddressLoop(
            address = address,
            shell = { script, timeout ->
                assertEquals(RootShell.DEFAULT_TIMEOUT_MILLIS, timeout)
                scripts += script
                shellTimes += now
                answer(script)
            },
            hasAddress = { name, wanted -> addresses[name]?.contains(wanted) == true },
            findIface = { finds++; iface },
            eligible = { eligible },
            nowMillis = { now },
            rootDenied = rootDenied,
        )

        /** Runs ticks as the keeper's scheduler would until [untilMillis]. */
        fun runUntil(untilMillis: Long) {
            while (now < untilMillis) {
                val delay = loop.tick() ?: return
                now += delay
            }
        }
    }

    @Test fun addsTheMissingAddressOnceAndThenOnlyWatches() {
        val phone = Phone()

        assertEquals(HotspotExtraAddressLoop.POLL_MILLIS, phone.loop.tick())
        assertEquals(State.Added("wlan2"), phone.loop.state)
        assertEquals(listOf("/system/bin/ip -4 addr replace 100.109.220.253/32 dev wlan2"), phone.scripts)

        phone.runUntil(phone.now + 60_000)
        assertEquals(1, phone.scripts.size)
        assertEquals(State.Added("wlan2"), phone.loop.state)
    }

    @Test fun anAddressThatIsAlreadyThereNeedsNoRoot() {
        val phone = Phone()
        phone.addresses["wlan2"] = mutableSetOf(address)

        phone.loop.tick()

        assertEquals(State.Added("wlan2"), phone.loop.state)
        assertTrue(phone.scripts.isEmpty())
    }

    @Test fun addsTheAddressAgainAfterTheHotspotRestarts() {
        val phone = Phone()
        phone.loop.tick()
        // Tethering restarts: the interface goes away, then comes back without the extra address.
        phone.iface = null
        phone.addresses.clear()
        phone.loop.tick()
        assertEquals(State.NoHotspot, phone.loop.state)
        phone.iface = "wlan2"

        phone.loop.tick()

        assertEquals(State.Added("wlan2"), phone.loop.state)
        assertEquals(2, phone.scripts.size)
        assertTrue(phone.addresses.getValue("wlan2").contains(address))
    }

    @Test fun addsTheAddressAgainWhenItDisappearsFromARunningHotspot() {
        val phone = Phone()
        phone.loop.tick()
        phone.addresses.getValue("wlan2").clear()

        phone.loop.tick()

        assertEquals(2, phone.scripts.size)
        assertEquals(State.Added("wlan2"), phone.loop.state)
    }

    @Test fun followsTheHotspotToANewInterface() {
        val phone = Phone()
        phone.loop.tick()
        phone.iface = "swlan0"

        phone.loop.tick()

        assertEquals("/system/bin/ip -4 addr replace 100.109.220.253/32 dev swlan0", phone.scripts.last())
        assertEquals(State.Added("swlan0"), phone.loop.state)
    }

    @Test fun refusedRootStopsTheLoopUntilTheDriverTriesAgain() {
        val phone = Phone()
        phone.answer = { RootShell.Result.Unavailable }

        assertNull(phone.loop.tick())
        assertEquals(State.RootDenied, phone.loop.state)
        repeat(3) { assertNull(phone.loop.tick()) }
        assertEquals(1, phone.scripts.size)
        assertEquals(1, phone.finds)

        phone.answer = { RootShell.Result.Done(0, "").also { phone.addresses.getOrPut("wlan2") { mutableSetOf() } += address } }
        phone.loop.clearDenied()
        phone.loop.tick()
        assertEquals(2, phone.scripts.size)
        assertEquals(State.Added("wlan2"), phone.loop.state)
    }

    @Test fun aSessionAfterARefusalStartsStoppedAndNeverProbes() {
        val phone = Phone(rootDenied = true)

        assertEquals(State.RootDenied, phone.loop.state)
        repeat(3) { assertNull(phone.loop.tick()) }
        assertTrue(phone.scripts.isEmpty())
        assertEquals(0, phone.finds)

        phone.loop.clearDenied()
        phone.loop.tick()
        assertEquals(State.Added("wlan2"), phone.loop.state)
        assertEquals(1, phone.scripts.size)
    }

    @Test fun failuresBackOffFromFiveToSixtySecondsAndStillWatchBetweenTries() {
        val phone = Phone()
        phone.answer = { RootShell.Result.Done(2, "RTNETLINK answers: Operation not permitted") }
        val start = phone.now

        phone.runUntil(start + 300_000)

        // 5 s, 10 s, 20 s, 40 s, then every 60 s.
        assertEquals(listOf(0L, 5_000L, 15_000L, 35_000L, 75_000L, 135_000L, 195_000L, 255_000L),
            phone.shellTimes.map { it - start })
        assertTrue(phone.loop.state is State.Failed)
        // Between tries it still looks every 5 s, so a restart or a returning address is noticed.
        assertTrue(phone.finds >= 300_000 / HotspotExtraAddressLoop.POLL_MILLIS)
    }

    @Test fun successAndANewHotspotResetTheBackoff() {
        val phone = Phone()
        var fail = true
        val add = phone.answer
        phone.answer = { if (fail) RootShell.Result.TimedOut else add(it) }
        phone.loop.tick()
        phone.now += 5_000
        phone.loop.tick()
        assertEquals(2, phone.scripts.size)
        assertTrue(phone.loop.state is State.Failed)

        // The hotspot goes away during the backoff and comes back: the next try is immediate.
        phone.iface = null
        phone.loop.tick()
        phone.iface = "wlan2"
        fail = false
        phone.now += 1
        phone.loop.tick()
        assertEquals(3, phone.scripts.size)
        assertEquals(State.Added("wlan2"), phone.loop.state)

        // After a success the next failure waits 5 s again, not the 10 s the earlier failures had reached.
        fail = true
        phone.addresses.clear()
        val failedAt = phone.now
        phone.loop.tick()
        assertEquals(4, phone.scripts.size)
        phone.now = failedAt + 4_999
        assertEquals(1L, phone.loop.tick())
        assertEquals(4, phone.scripts.size)
        phone.now = failedAt + 5_000
        phone.loop.tick()
        assertEquals(5, phone.scripts.size)
    }

    @Test fun anIpThatReportsSuccessWithoutTheAddressIsAFailure() {
        val phone = Phone()
        phone.answer = { RootShell.Result.Done(0, "") }

        assertEquals(HotspotExtraAddressLoop.POLL_MILLIS, phone.loop.tick())

        assertEquals(State.Failed("address missing after ip on wlan2"), phone.loop.state)
    }

    @Test fun noHotspotOrAnUnsafeInterfaceNameNeverReachesRoot() {
        val phone = Phone()
        phone.iface = null
        phone.loop.tick()
        assertEquals(State.NoHotspot, phone.loop.state)
        phone.iface = "wlan2;reboot"
        phone.loop.tick()
        assertEquals(State.NoHotspot, phone.loop.state)

        assertTrue(phone.scripts.isEmpty())
    }

    @Test fun anIneligibleLinkIsNeitherProbedNorChanged() {
        val phone = Phone()
        phone.eligible = false

        assertEquals(HotspotExtraAddressLoop.POLL_MILLIS, phone.loop.tick())

        assertEquals(State.Off, phone.loop.state)
        assertEquals(0, phone.finds)
        assertTrue(phone.scripts.isEmpty())
    }

    @Test fun removeDeletesOnceWhereTheAddressWasSeen() {
        val phone = Phone()
        phone.loop.tick()

        assertTrue(phone.loop.remove())
        assertFalse(phone.loop.remove())

        assertEquals("/system/bin/ip -4 addr del 100.109.220.253/32 dev wlan2", phone.scripts.last())
        assertEquals(2, phone.scripts.size)
        assertFalse(phone.addresses.getValue("wlan2").contains(address))
    }

    @Test fun removeSkipsAGoneAddressAndARefusedRoot() {
        val gone = Phone()
        gone.loop.tick()
        gone.addresses.clear()
        assertFalse(gone.loop.remove())
        assertEquals(1, gone.scripts.size)

        val refused = Phone()
        refused.answer = { RootShell.Result.Unavailable }
        refused.loop.tick()
        assertFalse(refused.loop.remove())
        assertEquals(1, refused.scripts.size)

        val never = Phone()
        never.iface = null
        never.loop.tick()
        assertFalse(never.loop.remove())
        assertTrue(never.scripts.isEmpty())
    }

    private fun shizukuLoop(fake: HotspotAddressBackendTest.FakeShizuku, addresses: MutableSet<Inet4Address>,
        iface: () -> String? = { "wlan2" }) = HotspotExtraAddressLoop(
        address = address,
        backend = ShizukuHotspotAddressBackend(fake),
        hasAddress = { name, wanted -> name == "wlan2" && wanted in addresses },
        findIface = iface,
        eligible = { true },
        nowMillis = { 0L },
    )

    @Test fun shizukuFileExistsCountsAsAdded() {
        val fake = HotspotAddressBackendTest.FakeShizuku()
        fake.error = IllegalStateException("android.os.ServiceSpecificException: File exists (code 17)")
        val loop = shizukuLoop(fake, mutableSetOf())

        assertEquals(HotspotExtraAddressLoop.POLL_MILLIS, loop.tick())

        assertEquals(State.Added("wlan2"), loop.state)
    }

    @Test fun shizukuReAddsAfterTheHotspotRestarts() {
        val fake = HotspotAddressBackendTest.FakeShizuku()
        val addresses = mutableSetOf<Inet4Address>()
        fake.onCall = { _, added -> addresses += added }
        var iface: String? = "wlan2"
        val loop = shizukuLoop(fake, addresses) { iface }
        loop.tick()
        iface = null
        addresses.clear()
        loop.tick()
        assertEquals(State.NoHotspot, loop.state)
        iface = "wlan2"

        loop.tick()

        assertEquals(State.Added("wlan2"), loop.state)
        assertEquals(listOf("wlan2:tp", "wlan2:tp"), fake.calls.map { it.first })
        assertFalse(loop.remove()) // Shizuku cannot remove it
        assertEquals(2, fake.calls.size)
    }

    @Test fun shizukuWaitingStatesDoNotBackOffAndABlockedRomStops() {
        val fake = HotspotAddressBackendTest.FakeShizuku()
        val loop = shizukuLoop(fake, mutableSetOf())
        fake.running = false
        repeat(3) { assertEquals(HotspotExtraAddressLoop.POLL_MILLIS, loop.tick()) }
        assertEquals(State.ShizukuUnavailable, loop.state)
        fake.running = true
        fake.granted = false
        repeat(3) { assertEquals(HotspotExtraAddressLoop.POLL_MILLIS, loop.tick()) }
        assertEquals(State.ShizukuPermissionNeeded, loop.state)
        assertTrue(fake.calls.isEmpty())

        fake.granted = true
        fake.error = SecurityException("blocked")
        assertNull(loop.tick())
        assertEquals(State.Blocked, loop.state)
        assertNull(loop.tick())
        assertEquals(1, fake.calls.size)
    }

    @Test fun backoffSchedule() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L),
            (1..7).map(HotspotExtraAddressLoop::backoffMillis))
    }
}
