package com.shilapi.xcertplay.network

import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.network.HotspotExtraAddressKeeper.State
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class HotspotExtraAddressKeeperTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val first = HotspotExtraAddress.parse("100.109.220.253")!!
    private val second = HotspotExtraAddress.parse("100.64.1.2")!!
    private val scripts = CopyOnWriteArrayList<String>()
    private val timeouts = CopyOnWriteArrayList<Long>()
    private val published = CopyOnWriteArrayList<State>()
    private val onInterface = ConcurrentHashMap.newKeySet<Inet4Address>()
    @Volatile private var root = true
    @Volatile private var iface: String? = "wlan2"
    @Volatile private var eligible = true
    @Volatile private var finders = 0
    @Volatile private var closedFinders = 0
    private val listener: (State) -> Unit = { published += it }

    @Before fun setUp() {
        HotspotExtraAddressKeeper.dependencies = HotspotExtraAddressKeeper.Dependencies(
            shell = { script, timeout ->
                scripts += script
                timeouts += timeout
                if (!root) RootShell.Result.Unavailable else {
                    val target = HotspotExtraAddress.parse(script.substringAfter(" addr ").split(' ')[1].substringBefore('/'))
                    when {
                        target == null -> RootShell.Result.Done(0, "0") // the root check
                        " replace " in script -> RootShell.Result.Done(0, "").also { onInterface += target }
                        else -> RootShell.Result.Done(0, "").also { onInterface -= target }
                    }
                }
            },
            hasAddress = { name, address -> name == iface && address in onInterface },
            openFinder = {
                finders++
                object : HotspotExtraAddressKeeper.HotspotFinder {
                    override fun find(): String? = iface
                    override fun close() { closedFinders++ }
                }
            },
            nowMillis = { 0L },
            log = {},
        )
        HotspotExtraAddressKeeper.addListener(listener)
    }

    @After fun tearDown() {
        HotspotExtraAddressKeeper.stop(removeAddress = false)
        HotspotExtraAddressKeeper.removeListener(listener)
        HotspotExtraAddressKeeper.dependencies = HotspotExtraAddressKeeper.Dependencies()
    }

    @Test fun startAddsTheAddressOnTheWorkerAndPublishesIt() {
        HotspotExtraAddressKeeper.start(context, first, eligible = { eligible })

        await { HotspotExtraAddressKeeper.state == State.Added("wlan2") }
        assertEquals(listOf("/system/bin/ip -4 addr replace 100.109.220.253/32 dev wlan2"), scripts)
        assertEquals(listOf(RootShell.DEFAULT_TIMEOUT_MILLIS), timeouts)
        assertTrue(HotspotExtraAddressKeeper.running)
        assertTrue(State.Added("wlan2") in published)
    }

    @Test fun aTetheringBroadcastChecksAgainWithoutWaitingForThePoll() {
        HotspotExtraAddressKeeper.start(context, first)
        await { HotspotExtraAddressKeeper.state == State.Added("wlan2") }
        onInterface.clear() // the hotspot restarted

        context.sendBroadcast(Intent("android.net.conn.TETHER_STATE_CHANGED"))
        shadowOf(Looper.getMainLooper()).idle()

        // The 5 s poll cannot explain a second add within this wait.
        await(2_000) { scripts.size == 2 && first in onInterface }
        onInterface.clear()
        context.sendBroadcast(Intent("android.net.wifi.WIFI_AP_STATE_CHANGED"))
        shadowOf(Looper.getMainLooper()).idle()
        await(2_000) { scripts.size == 3 && first in onInterface }
    }

    @Test fun stoppingAtTheEndOfASessionKeepsTheAddress() {
        HotspotExtraAddressKeeper.start(context, first)
        await { first in onInterface }

        HotspotExtraAddressKeeper.stop(removeAddress = false)

        await { closedFinders == finders }
        assertEquals(1, scripts.size)
        assertTrue(first in onInterface)
        assertFalse(HotspotExtraAddressKeeper.running)
        assertEquals(State.Off, HotspotExtraAddressKeeper.state)
        // Stopped means stopped: a broadcast no longer adds anything.
        onInterface.clear()
        context.sendBroadcast(Intent("android.net.conn.TETHER_STATE_CHANGED"))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        assertEquals(1, scripts.size)
    }

    @Test fun turningTheSettingOffRemovesTheAddressOnce() {
        HotspotExtraAddressKeeper.start(context, first)
        await { first in onInterface }

        HotspotExtraAddressKeeper.stop(removeAddress = true)
        HotspotExtraAddressKeeper.stop(removeAddress = true)

        await { first !in onInterface }
        await { closedFinders == finders }
        assertEquals(listOf("/system/bin/ip -4 addr replace 100.109.220.253/32 dev wlan2",
            "/system/bin/ip -4 addr del 100.109.220.253/32 dev wlan2"), scripts)
    }

    @Test fun aNewAddressReplacesTheOldOneInOrder() {
        HotspotExtraAddressKeeper.start(context, first)
        await { first in onInterface }

        HotspotExtraAddressKeeper.start(context, second)

        await { second in onInterface && first !in onInterface }
        assertEquals(listOf(
            "/system/bin/ip -4 addr replace 100.109.220.253/32 dev wlan2",
            "/system/bin/ip -4 addr del 100.109.220.253/32 dev wlan2",
            "/system/bin/ip -4 addr replace 100.64.1.2/32 dev wlan2",
        ), scripts)
        await { HotspotExtraAddressKeeper.state == State.Added("wlan2") }
    }

    @Test fun startingAgainOnlyChecksAndNeverRetriesARefusedRootUnlessAsked() {
        root = false
        HotspotExtraAddressKeeper.start(context, first)
        await { HotspotExtraAddressKeeper.state == State.RootDenied }

        HotspotExtraAddressKeeper.start(context, first)
        HotspotExtraAddressKeeper.checkNow()
        context.sendBroadcast(Intent("android.net.conn.TETHER_STATE_CHANGED"))
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(200)
        assertEquals(1, scripts.size)
        assertEquals(1, finders)

        root = true
        HotspotExtraAddressKeeper.start(context, first, retryRootDenied = true)
        await { HotspotExtraAddressKeeper.state == State.Added("wlan2") }
        assertEquals(2, scripts.size)
    }

    @Test fun anIneligibleLinkLeavesTheHotspotAlone() {
        eligible = false
        HotspotExtraAddressKeeper.start(context, first, eligible = { eligible })
        Thread.sleep(200)

        assertTrue(scripts.isEmpty())
        assertEquals(State.Off, HotspotExtraAddressKeeper.state)
        eligible = true
        HotspotExtraAddressKeeper.checkNow()
        await { first in onInterface }
    }

    @Test fun theRootCheckAsksOnceWithThePromptTimeout() {
        assertTrue(HotspotExtraAddressKeeper.checkRoot())
        assertEquals(listOf("id -u"), scripts)
        assertEquals(listOf(RootShell.PROMPT_TIMEOUT_MILLIS), timeouts)
        root = false
        assertFalse(HotspotExtraAddressKeeper.checkRoot())
    }

    private fun await(timeoutMillis: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("condition not met; scripts=$scripts state=${HotspotExtraAddressKeeper.state}")
            Thread.sleep(10)
        }
    }
}
