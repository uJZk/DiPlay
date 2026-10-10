package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.network.HotspotAddressBackend
import com.shilapi.xcertplay.network.HotspotAddressVpn
import com.shilapi.xcertplay.network.HotspotAddresses
import com.shilapi.xcertplay.network.HotspotExtraAddress
import com.shilapi.xcertplay.network.HotspotExtraAddressKeeper
import com.shilapi.xcertplay.network.RootShell
import com.shilapi.xcertplay.network.ShizukuSystem
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRunMode
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp",
    shadows = [HotspotExtraAddressSettingsTest.RootShellProbe::class,
        HotspotExtraAddressSettingsTest.HotspotProbe::class,
        HotspotExtraAddressSettingsTest.KeeperProbe::class,
        HotspotExtraAddressSettingsTest.VpnProbe::class,
        HotspotExtraAddressSettingsTest.ShizukuProbe::class])
class HotspotExtraAddressSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null
    private val defaultAddress = HotspotExtraAddress.parse(HotspotExtraAddress.DEFAULT)!!

    @Before fun setUp() {
        RootShellProbe.reset()
        HotspotProbe.reset()
        KeeperProbe.reset()
        VpnProbe.reset()
        ShizukuProbe.reset()
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
    }

    @After fun tearDown() {
        activity?.finish()
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        HotspotExtraAddressCard.rootCheckInProgress.set(false)
        listOf("diplay", "xcertplay_airplay", "tiplay_hotspot_address").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }

    // --- Persistence -------------------------------------------------------------------------------------------

    @Test fun theOldSwitchMigratesToTheRootMethodAndNormalIsTheDefault() {
        val prefs = context.getSharedPreferences("tiplay_hotspot_address", 0)
        assertEquals(HotspotAddressMethod.NORMAL, HotspotExtraAddressSettings.method(context))
        assertFalse(HotspotExtraAddressSettings.enabled(context))
        assertNull(HotspotExtraAddressSettings.providedAddress(context))

        prefs.edit().putBoolean("hotspot_extra_address_enabled", true).commit()
        assertEquals(HotspotAddressMethod.ROOT, HotspotExtraAddressSettings.method(context))
        assertTrue(HotspotExtraAddressSettings.enabled(context))
        assertEquals(defaultAddress, HotspotExtraAddressSettings.providedAddress(context))
        prefs.edit().putBoolean("hotspot_extra_address_enabled", false).commit()
        assertEquals(HotspotAddressMethod.NORMAL, HotspotExtraAddressSettings.method(context))

        // An unknown saved method (a later version's) falls back to the migration.
        prefs.edit().putBoolean("hotspot_extra_address_enabled", true).putString("hotspot_address_method", "LATER").commit()
        assertEquals(HotspotAddressMethod.ROOT, HotspotExtraAddressSettings.method(context))
    }

    @Test fun aDriverWhoHadTheOldSwitchOnStaysOnRootWithoutAnotherRootPrompt() {
        context.getSharedPreferences("tiplay_hotspot_address", 0).edit()
            .putBoolean("hotspot_extra_address_enabled", true).commit()

        val screen = openConnection()

        assertEquals(screen.getString(R.string.settings_hotspot_address_method) + " · " +
            screen.getString(R.string.settings_hotspot_method_root), chooserRow(screen).text.toString())
        assertTrue(screen.getString(R.string.settings_hotspot_method_root_description, HotspotExtraAddress.DEFAULT) in shown(screen))
        // Root was granted when the switch was turned on: the keeper starts on resume and nothing asks again.
        assertEquals(listOf(KeeperStart(defaultAddress, false, "root")), KeeperProbe.starts)
        assertTrue(RootShellProbe.scripts.isEmpty())
        assertEquals(HotspotAddressMethod.ROOT, HotspotExtraAddressSettings.method(context))
    }

    @Test fun theChosenMethodIsSavedByNameAndReplacesTheOldSwitch() {
        val prefs = context.getSharedPreferences("tiplay_hotspot_address", 0)
        prefs.edit().putBoolean("hotspot_extra_address_enabled", true).commit()

        for (method in HotspotAddressMethod.entries) {
            HotspotExtraAddressSettings.saveMethod(context, method)
            assertEquals(method, HotspotExtraAddressSettings.method(context))
            assertEquals(method.name, prefs.getString("hotspot_address_method", null))
            assertFalse(prefs.contains("hotspot_extra_address_enabled"))
            assertEquals(method != HotspotAddressMethod.NORMAL, HotspotExtraAddressSettings.enabled(context))
        }
        // The pre-chooser setter maps to Root and Normal.
        HotspotExtraAddressSettings.setEnabled(context, true)
        assertEquals(HotspotAddressMethod.ROOT, HotspotExtraAddressSettings.method(context))
        HotspotExtraAddressSettings.setEnabled(context, false)
        assertEquals(HotspotAddressMethod.NORMAL, HotspotExtraAddressSettings.method(context))
    }

    @Test fun theLiveAddressIsTheProvidedOneOnlyOnceTheMethodHasItInPlace() {
        assertNull("Normal provides nothing", HotspotExtraAddressSettings.liveAddress(context))

        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.ROOT)
        KeeperProbe.state = HotspotExtraAddressKeeper.State.NoHotspot
        assertNull(HotspotExtraAddressSettings.liveAddress(context))
        KeeperProbe.state = HotspotExtraAddressKeeper.State.Added("wlan2")
        assertEquals(defaultAddress, HotspotExtraAddressSettings.liveAddress(context))

        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.SHIZUKU)
        assertEquals(defaultAddress, HotspotExtraAddressSettings.liveAddress(context))

        // The VPN holds its own tunnel: the keeper's state does not count for it.
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.VPN)
        VpnProbe.state = HotspotAddressVpn.State.Starting
        assertNull(HotspotExtraAddressSettings.liveAddress(context))
        VpnProbe.state = HotspotAddressVpn.State.Up
        assertEquals(defaultAddress, HotspotExtraAddressSettings.liveAddress(context))
    }

    @Test fun bothAddressRangesAreSavedAndAnythingElseFallsBackToTheDefault() {
        HotspotExtraAddressSettings.saveAddress(context, ip("169.254.220.253"))
        assertEquals(ip("169.254.220.253"), HotspotExtraAddressSettings.address(context))
        context.getSharedPreferences("tiplay_hotspot_address", 0).edit().putString("hotspot_extra_address", "10.0.0.1").commit()
        assertEquals(defaultAddress, HotspotExtraAddressSettings.address(context))
    }

    // --- Placement and the search index ------------------------------------------------------------------------

    @Test fun theChooserIsATitleValueRowInConnectionOnlyInPhoneMode() {
        val screen = openSettings()
        val prefix = screen.getString(R.string.settings_hotspot_address_method) + " · "
        val overview = texts(screen).map { it.text.toString() }.toList()

        val pages = listOf(R.string.connection, R.string.settings_display, R.string.audio, R.string.settings_navigation,
            R.string.settings_vehicle, R.string.diagnostics, R.string.settings_advanced).associateWith { visibleIn(screen, it) }

        assertTrue(pages.getValue(R.string.connection).contains(prefix + screen.getString(R.string.settings_hotspot_method_normal)))
        (pages - R.string.connection).values.plusElement(overview).forEach { page ->
            assertFalse(page.any { it.startsWith(prefix) })
        }
        openCategory(screen, R.string.connection)
        val row = texts(screen).filterIsInstance<Button>().single { it.text.startsWith(prefix) }
        assertNotNull("a setting row has a chevron", row.compoundDrawablesRelative[2])
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals(SettingsCategory.CONNECTION,
            searchIndex(screen).single { it.title == screen.getString(R.string.settings_hotspot_address_method) }.category)
    }

    @Test fun headUnitModeHasNoHotspotAddressRowsAndStartsNothing() {
        useHeadUnitMode(context)
        for (method in HotspotAddressMethod.entries) {
            HotspotExtraAddressSettings.saveMethod(context, method)
            val screen = openSettings()

            val connection = visibleIn(screen, R.string.connection)

            assertFalse(connection.any { it.startsWith(screen.getString(R.string.settings_hotspot_address_method) + " · ") })
            assertFalse(searchIndex(screen).any { it.title == screen.getString(R.string.settings_hotspot_address_method) })
            assertTrue(HotspotProbe.threads.isEmpty())
            assertTrue(RootShellProbe.scripts.isEmpty())
            assertTrue(KeeperProbe.starts.isEmpty())
            assertTrue(VpnProbe.starts.isEmpty())
            assertEquals(0, ShizukuProbe.calls.get())
            screen.finish()
        }
    }

    @Test fun buildingTheSearchIndexTouchesNoRootShizukuVpnOrInterface() {
        for (method in HotspotAddressMethod.entries) {
            HotspotExtraAddressSettings.saveMethod(context, method)
            val screen = openSettings()
            HotspotProbe.reset(); KeeperProbe.reset(); VpnProbe.reset(); ShizukuProbe.reset(); RootShellProbe.reset()

            val index = searchIndex(screen)

            assertEquals(method.name, SettingsCategory.CONNECTION,
                index.single { it.title == screen.getString(R.string.settings_hotspot_address_method) }.category)
            assertEquals(method.name, method != HotspotAddressMethod.NORMAL,
                index.any { it.title == screen.getString(R.string.settings_hotspot_extra_address_value) })
            assertTrue("no interface probe", HotspotProbe.threads.isEmpty())
            assertTrue("no su", RootShellProbe.scripts.isEmpty())
            assertTrue("no keeper", KeeperProbe.starts.isEmpty() && KeeperProbe.stops.isEmpty())
            assertTrue("no VpnService", VpnProbe.starts.isEmpty() && VpnProbe.queries.get() == 0)
            assertEquals("no Shizuku", 0, ShizukuProbe.calls.get())
            screen.finish()
        }
    }

    // --- Normal ----------------------------------------------------------------------------------------------

    @Test fun normalShowsTheHotspotAddressAndWarnsWhenTheCarCannotOpenIt() {
        HotspotProbe.result = HotspotAddresses.Current("wlan2", listOf(ip("10.176.81.135")))
        val screen = openConnection()
        val blocked = screen.getString(R.string.settings_hotspot_address_blocked)

        assertTrue(shown(screen).contains(screen.getString(R.string.settings_hotspot_current_address, "10.176.81.135")))
        assertTrue(blocked in shown(screen))
        assertTrue(screen.getString(R.string.settings_hotspot_method_normal_description) in shown(screen))
        assertFalse(shown(screen).any { it.startsWith(screen.getString(R.string.settings_hotspot_extra_address_value) + " · ") })

        // A hotspot already in 100.64.0.0/10 needs nothing.
        HotspotProbe.result = HotspotAddresses.Current("wlan2", listOf(ip("100.70.1.1")))
        rerender(screen)
        assertFalse(blocked in shown(screen))

        // Another method says what it does instead.
        HotspotProbe.result = HotspotAddresses.Current("wlan2", listOf(ip("10.176.81.135")))
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.ROOT)
        rerender(screen)
        assertFalse(blocked in shown(screen))

        HotspotProbe.result = null
        rerender(screen)
        assertTrue(screen.getString(R.string.settings_hotspot_not_found) in shown(screen))
        assertTrue(KeeperProbe.starts.isEmpty() && VpnProbe.starts.isEmpty())
    }

    // --- Root ------------------------------------------------------------------------------------------------

    @Test
    @Config(sdk = [28, 33])
    fun choosingRootAsksForRootOffTheMainThreadAndAppliesAtOnce() {
        RootShellProbe.answer = RootShell.Result.Done(0, "0")
        val screen = openConnection()
        val session = mock(CarPlayController::class.java)
        `when`(session.phoneBrowserMode()).thenReturn(true)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        KeeperProbe.reset()

        val rootManager = CountDownLatch(1)
        RootShellProbe.gate = rootManager

        choose(screen, HotspotAddressMethod.ROOT)

        // While the root manager is asking, the line says so and nothing is saved yet.
        assertTrue(screen.getString(R.string.settings_hotspot_extra_address_root_checking) in shown(screen))
        assertEquals(HotspotAddressMethod.NORMAL, HotspotExtraAddressSettings.method(context))
        rootManager.countDown()
        finishRootCheck()
        assertEquals(listOf(HotspotExtraAddress.ROOT_CHECK_SCRIPT), RootShellProbe.scripts)
        assertEquals(listOf(RootShell.PROMPT_TIMEOUT_MILLIS), RootShellProbe.timeouts)
        assertNotEquals(Looper.getMainLooper().thread, RootShellProbe.threads.single())
        assertEquals(HotspotAddressMethod.ROOT, HotspotExtraAddressSettings.method(context))
        assertEquals(listOf(KeeperStart(defaultAddress, true, "root")), KeeperProbe.starts)
        assertTrue(chooserRow(screen).text.endsWith(screen.getString(R.string.settings_hotspot_method_root)))
        // Applies at once: no reconnect bar and the session keeps running.
        assertFalse(PendingReconnect.isPending(session))
        assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        assertEquals(0, stops)
        assertNull(shadowOf(screen).nextStartedActivity)
    }

    @Test fun refusedRootKeepsThePreviousMethodAndSaysWhy() {
        for (answer in listOf(RootShell.Result.Unavailable, RootShell.Result.TimedOut, RootShell.Result.Done(0, "2000"))) {
            RootShellProbe.reset()
            KeeperProbe.reset()
            RootShellProbe.answer = answer
            HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.VPN)
            val screen = openConnection()

            choose(screen, HotspotAddressMethod.ROOT)
            finishRootCheck()

            assertEquals(answer.toString(), HotspotAddressMethod.VPN, HotspotExtraAddressSettings.method(context))
            assertEquals(screen.getString(R.string.settings_hotspot_extra_address_root_refused),
                ShadowToast.getTextOfLatestToast())
            assertTrue(KeeperProbe.starts.isEmpty())
            screen.finish()
        }
    }

    @Test fun leavingRootRemovesItsAddressWithoutAnotherRootPrompt() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.ROOT)
        val screen = openConnection()
        assertEquals(listOf(KeeperStart(defaultAddress, false, "root")), KeeperProbe.starts) // started on resume
        KeeperProbe.running = true

        choose(screen, HotspotAddressMethod.NORMAL)

        assertEquals(HotspotAddressMethod.NORMAL, HotspotExtraAddressSettings.method(context))
        assertEquals(listOf(true), KeeperProbe.stops)
        assertTrue(RootShellProbe.scripts.isEmpty())
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    // --- Shizuku ---------------------------------------------------------------------------------------------

    @Test fun choosingShizukuStartsTheKeeperWithShizukuAndAsksForItsPermissionFromTheTap() {
        ShizukuProbe.running = true
        ShizukuProbe.granted = false
        val screen = openConnection()
        assertEquals(0, ShizukuProbe.requests.get())

        choose(screen, HotspotAddressMethod.SHIZUKU)
        awaitThreads("tiplay-shizuku-permission")

        assertEquals(HotspotAddressMethod.SHIZUKU, HotspotExtraAddressSettings.method(context))
        // A tap: it also retries a session the ROM blocked.
        assertEquals(listOf(KeeperStart(defaultAddress, true, "shizuku")), KeeperProbe.starts)
        assertEquals(1, ShizukuProbe.requests.get())
        assertTrue(RootShellProbe.scripts.isEmpty())
        assertTrue(screen.getString(R.string.settings_hotspot_method_shizuku_description, HotspotExtraAddress.DEFAULT) in shown(screen))

        // Opening the screen again never asks by itself.
        screen.finish()
        openConnection()
        Thread.sleep(100)
        assertEquals(1, ShizukuProbe.requests.get())
    }

    @Test fun shizukuStatusLinesAndTheAllowButton() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.SHIZUKU)
        val screen = openConnection()
        KeeperProbe.running = true
        val allow = screen.getString(R.string.settings_hotspot_shizuku_allow)
        fun line(state: HotspotExtraAddressKeeper.State): List<String> {
            KeeperProbe.state = state
            rerender(screen)
            return shown(screen)
        }

        val notRunning = line(HotspotExtraAddressKeeper.State.ShizukuUnavailable)
        assertTrue(screen.getString(R.string.settings_hotspot_shizuku_not_running) in notRunning)
        assertFalse(allow in notRunning)
        assertTrue(screen.getString(R.string.settings_hotspot_shizuku_blocked) in line(HotspotExtraAddressKeeper.State.Blocked))
        assertTrue(screen.getString(R.string.settings_hotspot_extra_address_added, "wlan2") in
            line(HotspotExtraAddressKeeper.State.Added("wlan2")))
        val permission = line(HotspotExtraAddressKeeper.State.ShizukuPermissionNeeded)
        assertTrue(screen.getString(R.string.settings_hotspot_shizuku_permission) in permission)
        assertTrue(allow in permission)

        ShizukuProbe.running = true
        ShizukuProbe.granted = false
        texts(screen).filterIsInstance<Button>().single { it.text.toString() == allow }.performClick()
        awaitThreads("tiplay-shizuku-permission")
        assertEquals(1, ShizukuProbe.requests.get())

        // Refused with "don't ask again": only the Shizuku app can allow it now.
        ShizukuProbe.deniedForGood = true
        texts(screen).filterIsInstance<Button>().single { it.text.toString() == allow }.performClick()
        awaitThreads("tiplay-shizuku-permission")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, ShizukuProbe.requests.get())
        assertEquals(screen.getString(R.string.settings_hotspot_shizuku_denied), ShadowToast.getTextOfLatestToast())
    }

    @Test fun leavingShizukuSaysTheAddressStaysUntilTheHotspotRestarts() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.SHIZUKU)
        val screen = openConnection()
        KeeperProbe.running = true

        choose(screen, HotspotAddressMethod.NORMAL)

        assertEquals(listOf(true), KeeperProbe.stops) // the keeper asks; Shizuku cannot remove it
        assertEquals(screen.getString(R.string.settings_hotspot_shizuku_left), ShadowToast.getTextOfLatestToast())
    }

    @Test fun leavingShizukuForRootSaysNothingBecauseRootTakesTheAddressOver() {
        RootShellProbe.answer = RootShell.Result.Done(0, "0")
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.SHIZUKU)
        val screen = openConnection()
        KeeperProbe.reset()

        choose(screen, HotspotAddressMethod.ROOT)
        finishRootCheck()

        // Root keeps the same address and removes it when the driver leaves Root: nothing is left behind.
        assertEquals(HotspotAddressMethod.ROOT, HotspotExtraAddressSettings.method(context))
        assertEquals(listOf(KeeperStart(defaultAddress, true, "root")), KeeperProbe.starts)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    // --- VPN -------------------------------------------------------------------------------------------------

    @Test fun choosingVpnAsksForConsentOnlyFromTheChooser() {
        VpnProbe.startState = HotspotAddressVpn.State.NeedsConsent
        VpnProbe.consent = Intent("android.net.vpn.CONSENT")
        val screen = openConnection()

        choose(screen, HotspotAddressMethod.VPN)

        assertEquals(HotspotAddressMethod.VPN, HotspotExtraAddressSettings.method(context))
        assertEquals(listOf(defaultAddress), VpnProbe.starts)
        assertEquals("android.net.vpn.CONSENT", shadowOf(screen).nextStartedActivity?.action)
        assertTrue(KeeperProbe.starts.isEmpty())
        assertTrue(screen.getString(R.string.settings_hotspot_vpn_consent) in shown(screen))

        // Back from the dialog without consent: the resume only reports it, and the button asks again.
        screen.finish()
        val again = openConnection()
        assertNull(shadowOf(again).nextStartedActivity)
        val allow = texts(again).filterIsInstance<Button>().single { it.text.toString() == again.getString(R.string.settings_hotspot_vpn_allow) }
        assertTrue(allow.isShown)
        allow.performClick()
        assertEquals("android.net.vpn.CONSENT", shadowOf(again).nextStartedActivity?.action)
    }

    @Test fun anotherVpnOrTheWiredCarPlayVpnIsNeverReplaced() {
        VpnProbe.consent = Intent("android.net.vpn.CONSENT")
        for ((conflict, state, text) in listOf(
            Triple(HotspotAddressVpn.Conflict.OTHER_VPN, HotspotAddressVpn.State.OtherVpn, R.string.settings_hotspot_vpn_other),
            Triple(HotspotAddressVpn.Conflict.WIRED_CARPLAY, HotspotAddressVpn.State.WiredCarPlayVpn, R.string.settings_hotspot_vpn_wired),
        )) {
            HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.NORMAL)
            VpnProbe.conflict = conflict
            VpnProbe.startState = state
            val screen = openConnection()

            choose(screen, HotspotAddressMethod.VPN)

            assertNull(shadowOf(screen).nextStartedActivity)
            assertTrue(screen.getString(text) in shown(screen))
            assertFalse(screen.getString(R.string.settings_hotspot_vpn_allow) in shown(screen))
            screen.finish()
        }
    }

    @Test fun vpnStatusLines() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.VPN)
        HotspotExtraAddressSettings.saveAddress(context, ip("169.254.220.253"))
        val screen = openConnection()
        fun line(state: HotspotAddressVpn.State): List<String> {
            VpnProbe.state = state
            rerender(screen)
            return shown(screen)
        }

        assertTrue(screen.getString(R.string.settings_hotspot_vpn_up, "169.254.220.253") in line(HotspotAddressVpn.State.Up))
        assertTrue(screen.getString(R.string.settings_hotspot_vpn_starting) in line(HotspotAddressVpn.State.Starting))
        assertTrue(screen.getString(R.string.settings_hotspot_vpn_revoked) in line(HotspotAddressVpn.State.Revoked))
        assertTrue(screen.getString(R.string.settings_hotspot_vpn_failed) in line(HotspotAddressVpn.State.Failed("x")))
        assertTrue(screen.getString(R.string.settings_hotspot_method_vpn_description, "169.254.220.253") in shown(screen))
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        assertTrue(screen.getString(R.string.settings_hotspot_extra_address_manual_only,
            screen.getString(R.string.built_in_car_hotspot)) in line(HotspotAddressVpn.State.Up))
    }

    @Test fun leavingVpnClosesTheTunnel() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.VPN)
        val screen = openConnection()
        VpnProbe.active = true

        choose(screen, HotspotAddressMethod.SHIZUKU)

        assertEquals(1, VpnProbe.stops.get())
        assertEquals(listOf(KeeperStart(defaultAddress, true, "shizuku")), KeeperProbe.starts)
    }

    @Test fun aRevokedVpnStaysOffUntilTheDriverTapsAgain() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.VPN)
        VpnProbe.state = HotspotAddressVpn.State.Revoked
        VpnProbe.startState = HotspotAddressVpn.State.Starting
        val screen = openConnection()

        // The resume asked without a tap, so the driver's choice in Android settings stands.
        assertEquals(listOf(false), VpnProbe.retries)
        assertTrue(screen.getString(R.string.settings_hotspot_vpn_revoked) in shown(screen))
        val allow = texts(screen).filterIsInstance<Button>().single { it.text.toString() == screen.getString(R.string.settings_hotspot_vpn_allow) }
        assertTrue(allow.isShown)

        allow.performClick()

        assertEquals(listOf(false, true), VpnProbe.retries)
        assertEquals(HotspotAddressVpn.State.Starting, VpnProbe.state)
        assertNull(shadowOf(screen).nextStartedActivity) // Android still allows the VPN: no consent screen

        // Choosing VPN again in the chooser is a tap too.
        VpnProbe.state = HotspotAddressVpn.State.Revoked
        choose(screen, HotspotAddressMethod.VPN)
        assertTrue(VpnProbe.retries.last())
        assertEquals(HotspotAddressVpn.State.Starting, VpnProbe.state)
    }

    @Test fun theVpnRunsOnlyOnThePhonesOwnHotspotLikeTheKeeper() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.VPN)
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)

        HotspotExtraAddressSettings.sync(context)
        Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)

        assertTrue(VpnProbe.starts.isEmpty())
        VpnProbe.active = true
        HotspotExtraAddressSettings.sync(context) // a tunnel from before the link changed closes
        assertEquals(1, VpnProbe.stops.get())

        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        HotspotExtraAddressSettings.sync(context)
        assertEquals(listOf(defaultAddress), VpnProbe.starts)
    }

    @Test
    @Config(sdk = [34])
    fun theVpnHintAppearsWhereAndroidDropsHotspotTrafficToVpnAddresses() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.VPN)
        ShadowBuild.setVersionSecurityPatch("2025-02-05")
        val screen = openConnection()
        val hint = screen.getString(R.string.settings_hotspot_vpn_unlikely_hint, HotspotExtraAddress.LINK_LOCAL_SUGGESTION)
        val unlikely = screen.getString(R.string.settings_hotspot_method_vpn_unlikely)

        assertTrue(hint in shown(screen))
        chooserRow(screen).performClick()
        val items = ShadowAlertDialog.getLatestAlertDialog().listView.adapter.let { adapter ->
            (0 until adapter.count).map { adapter.getItem(it).toString() }
        }
        assertEquals(listOf(R.string.settings_hotspot_method_normal, R.string.settings_hotspot_method_root,
            R.string.settings_hotspot_method_vpn, R.string.settings_hotspot_method_shizuku).map(screen::getString),
            items.map { it.substringBefore('\n') })
        assertEquals("${screen.getString(R.string.settings_hotspot_method_vpn)}\n$unlikely", items[2])
        ShadowAlertDialog.getLatestAlertDialog().dismiss()

        // Link-local addresses are exempt.
        HotspotExtraAddressSettings.saveAddress(context, ip("169.254.220.253"))
        rerender(screen)
        assertFalse(hint in shown(screen))

        // Android 14 before the January 2025 patch accepts the traffic.
        HotspotExtraAddressSettings.saveAddress(context, defaultAddress)
        ShadowBuild.setVersionSecurityPatch("2024-12-05")
        rerender(screen)
        assertFalse(hint in shown(screen))
    }

    // --- Shared rows -----------------------------------------------------------------------------------------

    @Test fun theAddressRowAcceptsBothRangesAndMovesTheRunningMethod() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.ROOT)
        val screen = openConnection()
        val prefix = screen.getString(R.string.settings_hotspot_extra_address_value) + " · "
        KeeperProbe.reset()

        texts(screen).filterIsInstance<Button>().single { it.text.toString() == prefix + HotspotExtraAddress.DEFAULT }
            .performClick()
        shadowOf(Looper.getMainLooper()).idle() // delivers the dialog's show callback
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        assertEquals(HotspotExtraAddress.DEFAULT, input.text.toString())
        for (invalid in listOf("100.128.0.1", "10.0.0.1", "169.255.0.1", "1.2.3", "tesla.local", "100.109.220.253;reboot", "")) {
            input.setText(invalid)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(invalid, screen.getString(R.string.settings_hotspot_extra_address_invalid), input.error?.toString())
            assertTrue(invalid, dialog.isShowing)
        }
        assertEquals(defaultAddress, HotspotExtraAddressSettings.address(context))
        assertTrue(KeeperProbe.starts.isEmpty())

        input.setText(" 169.254.1.2 ")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        assertFalse(dialog.isShowing)
        assertEquals(ip("169.254.1.2"), HotspotExtraAddressSettings.address(context))
        assertEquals(listOf(KeeperStart(ip("169.254.1.2"), false, "root")), KeeperProbe.starts)
        assertTrue(texts(screen).any { it.text.toString() == prefix + "169.254.1.2" })
        assertTrue(RootShellProbe.scripts.isEmpty())
    }

    @Test fun theKeeperLineSaysWhatRootDoes() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.ROOT)
        val screen = openConnection()
        fun line(state: HotspotExtraAddressKeeper.State, running: Boolean = true): List<String> {
            KeeperProbe.state = state
            KeeperProbe.running = running
            rerender(screen)
            return shown(screen)
        }

        assertTrue(screen.getString(R.string.settings_hotspot_extra_address_added, "wlan2") in
            line(HotspotExtraAddressKeeper.State.Added("wlan2")))
        assertTrue(screen.getString(R.string.settings_hotspot_extra_address_no_root) in
            line(HotspotExtraAddressKeeper.State.RootDenied))
        assertTrue(screen.getString(R.string.settings_hotspot_extra_address_failed) in
            line(HotspotExtraAddressKeeper.State.Failed("ip exited with 2 on wlan2")))
        assertTrue(screen.getString(R.string.settings_hotspot_extra_address_waiting) in
            line(HotspotExtraAddressKeeper.State.NoHotspot))
        assertTrue(screen.getString(R.string.settings_hotspot_extra_address_paused) in
            line(HotspotExtraAddressKeeper.State.Off, running = false))
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
        assertTrue(screen.getString(R.string.settings_hotspot_extra_address_manual_only,
            screen.getString(R.string.built_in_car_hotspot)) in line(HotspotExtraAddressKeeper.State.Off))
    }

    @Test fun theDiagnosticLineNamesTheMethodAndTheVpnRuleButNeverTheAddress() {
        HotspotExtraAddressSettings.saveAddress(context, ip("169.254.1.2"))
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.VPN)
        VpnProbe.state = HotspotAddressVpn.State.Up

        val line = HotspotExtraAddressSettings.diagnosticLine(context)

        assertTrue(line, line.contains("method=VPN"))
        assertTrue(line, line.contains("vpn=up"))
        assertTrue(line, line.contains("vpnRule=before-android14")) // SDK 29
        assertTrue(line, line.contains("range=link-local"))
        assertFalse(line, line.contains("169.254.1.2"))
        assertFalse(line, line.contains(HotspotExtraAddress.DEFAULT))
    }

    // --- Lifecycle -------------------------------------------------------------------------------------------

    @Test fun leavingPhoneModeStopsTheKeeperAndTheVpn() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.ROOT)
        val screen = openConnection()
        KeeperProbe.running = true
        VpnProbe.active = true

        // Exercise the retained backend transition; the product no longer exposes this switch.
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.HEAD_UNIT)
        HotspotExtraAddressSettings.sync(context)
        TeslaBrowserLink.sync(context)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "markReconnectNeeded")

        assertEquals(CarPlayRunMode.HEAD_UNIT, AirPlayPersistence.loadRunMode(context))
        assertEquals(listOf(true), KeeperProbe.stops)
        assertEquals(1, VpnProbe.stops.get())
        assertEquals(HotspotAddressMethod.ROOT, HotspotExtraAddressSettings.method(context)) // back in phone mode it starts again
    }

    @Test fun leavingPhoneModeDuringAPhoneSessionKeepsTheAddressUntilThatSessionEnds() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.ROOT)
        val screen = openConnection()
        val session = storeSession(phoneBrowser = true)
        KeeperProbe.reset()
        KeeperProbe.running = true
        KeeperProbe.backendId = "root"

        // Exercise the retained backend transition; the product no longer exposes this switch.
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.HEAD_UNIT)
        HotspotExtraAddressSettings.sync(context)
        TeslaBrowserLink.sync(context)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "markReconnectNeeded")

        // The run mode applies at the next connection, so the car's page keeps its address for this session.
        assertTrue(PendingReconnect.isPending(session))
        assertTrue(KeeperProbe.stops.isEmpty())
        val service = Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)
        assertEquals(KeeperStart(defaultAddress, false, "root"), KeeperProbe.starts.last())

        // That session ends: now the address goes, once.
        CarPlayBackgroundSession.clear(session)
        service.destroy()
        assertEquals(listOf(true), KeeperProbe.stops)
        assertTrue(RootShellProbe.scripts.isEmpty())
    }

    @Test fun theSessionServiceKeepsEachMethodsAddressWhenItStops() {
        for (method in listOf(HotspotAddressMethod.ROOT, HotspotAddressMethod.SHIZUKU)) {
            KeeperProbe.reset()
            HotspotExtraAddressSettings.saveMethod(context, method)
            val phone = Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)
            val id = if (method == HotspotAddressMethod.ROOT) "root" else "shizuku"
            assertEquals(listOf(KeeperStart(defaultAddress, false, id)), KeeperProbe.starts)
            KeeperProbe.backendId = id
            phone.destroy()
            assertEquals(listOf(false), KeeperProbe.stops)
        }

        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.VPN)
        KeeperProbe.reset()
        val vpn = Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)
        assertEquals(listOf(defaultAddress), VpnProbe.starts)
        VpnProbe.active = true
        vpn.destroy()
        assertEquals(0, VpnProbe.stops.get()) // the car's page keeps its VPN address between sessions

        useHeadUnitMode(context)
        VpnProbe.reset()
        KeeperProbe.reset()
        val headUnit = Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)
        headUnit.destroy()
        assertTrue(KeeperProbe.starts.isEmpty())
        assertTrue(VpnProbe.starts.isEmpty())
        assertTrue(RootShellProbe.scripts.isEmpty())
    }

    @Test fun aHeadUnitSessionStartsNothingUntilThePhoneModeConnection() {
        HotspotExtraAddressSettings.saveMethod(context, HotspotAddressMethod.ROOT)
        val session = storeSession(phoneBrowser = false) // connected in head-unit mode
        openConnection()
        val headUnit = Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)

        assertTrue(KeeperProbe.starts.isEmpty())

        CarPlayBackgroundSession.clear(session)
        headUnit.destroy()
        storeSession(phoneBrowser = true)
        Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)
        assertEquals(listOf(KeeperStart(defaultAddress, false, "root")), KeeperProbe.starts)
    }

    // --- Probes ----------------------------------------------------------------------------------------------

    data class KeeperStart(val address: Inet4Address, val retryRootDenied: Boolean, val backend: String)

    @Implements(RootShell::class, isInAndroidSdk = false)
    class RootShellProbe {
        @Implementation fun run(script: String, timeoutMillis: Long): RootShell.Result {
            scripts += script
            timeouts += timeoutMillis
            threads += Thread.currentThread()
            gate?.await(3, TimeUnit.SECONDS) // the root manager's prompt
            return answer
        }

        companion object {
            val scripts = CopyOnWriteArrayList<String>()
            val timeouts = CopyOnWriteArrayList<Long>()
            val threads = CopyOnWriteArrayList<Thread>()
            @Volatile var answer: RootShell.Result = RootShell.Result.Unavailable
            @Volatile var gate: CountDownLatch? = null
            fun reset() {
                scripts.clear(); timeouts.clear(); threads.clear(); answer = RootShell.Result.Unavailable; gate = null
            }
        }
    }

    @Implements(HotspotAddresses::class, isInAndroidSdk = false)
    class HotspotProbe {
        @Implementation fun current(context: Context): HotspotAddresses.Current? {
            threads += Thread.currentThread()
            return result
        }

        companion object {
            val threads = CopyOnWriteArrayList<Thread>()
            @Volatile var result: HotspotAddresses.Current? = null
            fun reset() { threads.clear(); result = null }
        }
    }

    @Implements(HotspotExtraAddressKeeper::class, isInAndroidSdk = false)
    class KeeperProbe {
        @Implementation fun start(context: Context, address: Inet4Address, eligible: () -> Boolean, retryRootDenied: Boolean,
            backend: HotspotAddressBackend?) {
            starts += KeeperStart(address, retryRootDenied, backend?.id ?: "root")
        }

        @Implementation fun stop(removeAddress: Boolean) { stops += removeAddress }

        @Implementation fun checkNow() { checks.incrementAndGet() }

        @Implementation fun getRunning(): Boolean = running

        @Implementation fun getState(): HotspotExtraAddressKeeper.State = state

        @Implementation fun getBackendId(): String? = backendId

        companion object {
            val starts = CopyOnWriteArrayList<KeeperStart>()
            val stops = CopyOnWriteArrayList<Boolean>()
            val checks = AtomicInteger()
            @Volatile var running = false
            @Volatile var backendId: String? = null
            @Volatile var state: HotspotExtraAddressKeeper.State = HotspotExtraAddressKeeper.State.Off
            fun reset() {
                starts.clear(); stops.clear(); checks.set(0); running = false; backendId = null
                state = HotspotExtraAddressKeeper.State.Off
            }
        }
    }

    @Implements(HotspotAddressVpn::class, isInAndroidSdk = false)
    class VpnProbe {
        @Implementation fun start(context: Context, address: Inet4Address, retry: Boolean) {
            starts += address
            retries += retry
            // Like the real one: a revoke stays until a tap.
            if (state != HotspotAddressVpn.State.Revoked || retry) state = startState
        }

        @Implementation fun stop() {
            stops.incrementAndGet()
            state = HotspotAddressVpn.State.Off
            active = false
        }

        @Implementation fun getState(): HotspotAddressVpn.State = state

        @Implementation fun getActive(): Boolean = active

        @Implementation fun getRequested(): Inet4Address? = null

        @Implementation fun conflict(context: Context): HotspotAddressVpn.Conflict {
            queries.incrementAndGet()
            return conflict
        }

        @Implementation fun consentIntent(context: Context): Intent? {
            queries.incrementAndGet()
            return consent
        }

        companion object {
            val starts = CopyOnWriteArrayList<Inet4Address>()
            val retries = CopyOnWriteArrayList<Boolean>()
            val stops = AtomicInteger()
            val queries = AtomicInteger()
            @Volatile var state: HotspotAddressVpn.State = HotspotAddressVpn.State.Off
            @Volatile var startState: HotspotAddressVpn.State = HotspotAddressVpn.State.NeedsConsent
            @Volatile var active = false
            @Volatile var conflict = HotspotAddressVpn.Conflict.NONE
            @Volatile var consent: Intent? = null
            fun reset() {
                starts.clear(); retries.clear(); stops.set(0); queries.set(0); state = HotspotAddressVpn.State.Off
                startState = HotspotAddressVpn.State.NeedsConsent; active = false
                conflict = HotspotAddressVpn.Conflict.NONE; consent = null
            }
        }
    }

    @Implements(ShizukuSystem::class, isInAndroidSdk = false)
    class ShizukuProbe {
        @Implementation fun running(): Boolean = running.also { calls.incrementAndGet() }

        @Implementation fun permissionGranted(): Boolean = granted.also { calls.incrementAndGet() }

        @Implementation fun permissionDeniedForGood(): Boolean = deniedForGood.also { calls.incrementAndGet() }

        @Implementation fun requestPermission(): Boolean {
            calls.incrementAndGet()
            requests.incrementAndGet()
            return true
        }

        companion object {
            val calls = AtomicInteger()
            val requests = AtomicInteger()
            @Volatile var running = false
            @Volatile var granted = false
            @Volatile var deniedForGood = false
            fun reset() { calls.set(0); requests.set(0); running = false; granted = false; deniedForGood = false }
        }
    }

    // --- Helpers ---------------------------------------------------------------------------------------------

    private fun choose(screen: DiPlayActivity, method: HotspotAddressMethod) {
        chooserRow(screen).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.listView.performItemClick(null, method.ordinal, method.ordinal.toLong())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        finishProbes()
    }

    private fun chooserRow(screen: DiPlayActivity): Button = texts(screen).filterIsInstance<Button>()
        .single { it.text.startsWith(screen.getString(R.string.settings_hotspot_address_method) + " · ") }

    private fun awaitThreads(name: String) {
        val deadline = System.currentTimeMillis() + 3_000
        while (Thread.getAllStackTraces().keys.any { it.name == name && it.isAlive } && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun finishProbes() {
        HotspotProbe.threads.forEach { it.join(3_000) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun finishRootCheck() {
        val deadline = System.currentTimeMillis() + 3_000
        while (RootShellProbe.threads.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        RootShellProbe.threads.forEach { it.join(3_000) }
        while (HotspotExtraAddressCard.rootCheckInProgress.get() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        shadowOf(Looper.getMainLooper()).idle()
        finishProbes()
    }

    private fun rerender(screen: DiPlayActivity) {
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        finishProbes()
    }

    private fun storeSession(phoneBrowser: Boolean): CarPlayController =
        mock(CarPlayController::class.java).also { session ->
            `when`(session.phoneBrowserMode()).thenReturn(phoneBrowser)
            CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
                CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { }
            CarPlayBackgroundSession.active = true
        }

    private fun phoneModeSwitch(screen: DiPlayActivity): Switch =
        descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_phone_browser_mode) }

    private fun searchIndex(screen: DiPlayActivity) =
        ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(screen, "buildSettingsSearchIndex")

    private fun openCategory(screen: DiPlayActivity, category: Int) {
        descendants(screen.window.decorView).first { candidate ->
            candidate.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(category))
        }.performClick()
        finishProbes()
    }

    private fun openConnection(): DiPlayActivity = openSettings().also { openCategory(it, R.string.connection) }

    private fun visibleIn(screen: DiPlayActivity, category: Int): List<String> {
        openCategory(screen, category)
        return texts(screen).map { it.text.toString() }.toList().also { screen.onBackPressedDispatcher.onBackPressed() }
    }

    private fun shown(screen: DiPlayActivity): List<String> =
        texts(screen).filter { it.isShown }.map { it.text.toString() }.toList()

    private fun openSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { activity = it }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun ip(value: String) = InetAddress.getByName(value) as Inet4Address
}
