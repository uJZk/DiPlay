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
import com.shilapi.xcertplay.network.HotspotAddresses
import com.shilapi.xcertplay.network.HotspotExtraAddress
import com.shilapi.xcertplay.network.HotspotExtraAddressKeeper
import com.shilapi.xcertplay.network.RootShell
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRunMode
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
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
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp",
    shadows = [HotspotExtraAddressSettingsTest.RootShellProbe::class,
        HotspotExtraAddressSettingsTest.HotspotProbe::class,
        HotspotExtraAddressSettingsTest.KeeperProbe::class])
class HotspotExtraAddressSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null
    private val defaultAddress = HotspotExtraAddress.parse(HotspotExtraAddress.DEFAULT)!!

    @Before fun setUp() {
        RootShellProbe.reset()
        HotspotProbe.reset()
        KeeperProbe.reset()
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

    @Test fun theExtraAddressLivesInConnectionOnlyInPhoneMode() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        val screen = openSettings()
        val toggle = screen.getString(R.string.settings_hotspot_extra_address)
        val value = screen.getString(R.string.settings_hotspot_extra_address_value) + " · "
        val overview = texts(screen).map { it.text.toString() }.toList()

        val pages = listOf(R.string.connection, R.string.settings_display, R.string.audio, R.string.settings_navigation,
            R.string.settings_vehicle, R.string.diagnostics, R.string.settings_advanced).associateWith { visibleIn(screen, it) }

        assertTrue(toggle in pages.getValue(R.string.connection))
        assertTrue(pages.getValue(R.string.connection).any { it.startsWith(value + HotspotExtraAddress.DEFAULT) })
        (pages - R.string.connection).values.plusElement(overview).forEach { texts ->
            assertFalse(toggle in texts)
            assertFalse(texts.any { it.startsWith(value) })
        }
        val index = searchIndex(screen)
        assertEquals(SettingsCategory.CONNECTION, index.single { it.title == toggle }.category)
    }

    @Test fun headUnitModeHasNoHotspotAddressRowsAndProbesNothing() {
        HotspotExtraAddressSettings.setEnabled(context, true)
        val screen = openSettings()

        val connection = visibleIn(screen, R.string.connection)

        assertFalse(screen.getString(R.string.settings_hotspot_extra_address) in connection)
        assertFalse(connection.any { it.startsWith(screen.getString(R.string.settings_hotspot_extra_address_value) + " · ") })
        assertFalse(searchIndex(screen).any { it.title == screen.getString(R.string.settings_hotspot_extra_address) })
        assertTrue(HotspotProbe.threads.isEmpty())
        assertTrue(RootShellProbe.scripts.isEmpty())
        // A head unit never starts the keeper, even with the setting saved on.
        assertTrue(KeeperProbe.starts.isEmpty())
    }

    @Test fun buildingTheSearchIndexStartsNoRootProcessOrInterfaceProbe() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        val screen = openSettings()
        val title = screen.getString(R.string.settings_hotspot_extra_address)

        val index = searchIndex(screen)

        val result = index.single { it.title == title }
        assertEquals(SettingsCategory.CONNECTION, result.category)
        assertEquals(SettingsCategory.CONNECTION,
            index.single { it.title == screen.getString(R.string.settings_hotspot_extra_address_value) }.category)
        assertTrue("Building search metadata must not enumerate interfaces", HotspotProbe.threads.isEmpty())
        assertTrue("Building search metadata must not start su", RootShellProbe.scripts.isEmpty())

        // The card the result opens does probe the hotspot, on a thread, but still asks for no root.
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "openSearchResult",
            ReflectionHelpers.ClassParameter(result.javaClass, result))
        finishProbes()
        assertEquals(1, HotspotProbe.threads.size)
        assertNotEquals(Looper.getMainLooper().thread, HotspotProbe.threads.single())
        assertTrue(RootShellProbe.scripts.isEmpty())

        // With the setting on, the index still starts nothing.
        HotspotExtraAddressSettings.setEnabled(context, true)
        screen.onBackPressedDispatcher.onBackPressed()
        assertEquals(SettingsCategory.OVERVIEW, ReflectionHelpers.getField<SettingsCategory>(screen, "settingsCategory"))
        HotspotProbe.reset()
        KeeperProbe.reset()
        assertTrue(searchIndex(screen).any { it.title == title })
        assertTrue(HotspotProbe.threads.isEmpty())
        assertTrue(RootShellProbe.scripts.isEmpty())
        assertTrue(KeeperProbe.starts.isEmpty())
    }

    @Test fun theHotspotLineShowsItsAddressesAndWarnsWhenTheCarCannotOpenThem() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        HotspotProbe.result = HotspotAddresses.Current("wlan2", listOf(ip("10.176.81.135")))
        val screen = openConnection()
        val blocked = screen.getString(R.string.settings_hotspot_address_blocked)

        assertTrue(shown(screen).contains(screen.getString(R.string.settings_hotspot_current_address, "10.176.81.135")))
        assertTrue(blocked in shown(screen))

        HotspotProbe.result = HotspotAddresses.Current("wlan2", listOf(ip("10.176.81.135"), ip("100.109.220.253")))
        rerender(screen)
        assertTrue(shown(screen).contains(
            screen.getString(R.string.settings_hotspot_current_address, "10.176.81.135, 100.109.220.253")))
        assertFalse(blocked in shown(screen))

        HotspotProbe.result = null
        rerender(screen)
        assertTrue(screen.getString(R.string.settings_hotspot_not_found) in shown(screen))
        assertFalse(blocked in shown(screen))
    }

    @Test
    @Config(sdk = [28, 33])
    fun turningItOnAsksForRootOffTheMainThreadAndAppliesAtOnce() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        RootShellProbe.answer = RootShell.Result.Done(0, "0")
        val screen = openConnection()
        val session = mock(CarPlayController::class.java)
        `when`(session.phoneBrowserMode()).thenReturn(true)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        KeeperProbe.reset()

        extraAddressSwitch(screen).performClick()

        // While the root manager may be asking, the switch waits.
        assertFalse(extraAddressSwitch(screen).isEnabled)
        assertTrue(screen.getString(R.string.settings_hotspot_extra_address_root_checking) in shown(screen))
        finishRootCheck()
        assertEquals(listOf(HotspotExtraAddress.ROOT_CHECK_SCRIPT), RootShellProbe.scripts)
        assertEquals(listOf(RootShell.PROMPT_TIMEOUT_MILLIS), RootShellProbe.timeouts)
        assertNotEquals(Looper.getMainLooper().thread, RootShellProbe.threads.single())
        assertTrue(HotspotExtraAddressSettings.enabled(context))
        assertEquals(listOf(defaultAddress to true), KeeperProbe.starts)
        assertTrue(extraAddressSwitch(screen).isChecked)
        assertTrue(extraAddressSwitch(screen).isEnabled)
        // Applies at once: no reconnect bar and the session keeps running.
        assertFalse(PendingReconnect.isPending(session))
        assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
        assertEquals(0, stops)
        assertNull(shadowOf(screen).nextStartedActivity)
    }

    @Test fun refusedRootSwitchesBackOffAndSaysWhy() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        for (answer in listOf(RootShell.Result.Unavailable, RootShell.Result.TimedOut, RootShell.Result.Done(0, "2000"))) {
            RootShellProbe.reset()
            KeeperProbe.reset()
            RootShellProbe.answer = answer
            val screen = openConnection()

            extraAddressSwitch(screen).performClick()
            finishRootCheck()

            assertFalse(answer.toString(), HotspotExtraAddressSettings.enabled(context))
            assertFalse(answer.toString(), extraAddressSwitch(screen).isChecked)
            assertTrue(extraAddressSwitch(screen).isEnabled)
            assertEquals(screen.getString(R.string.settings_hotspot_extra_address_root_refused),
                ShadowToast.getTextOfLatestToast())
            assertTrue(KeeperProbe.starts.isEmpty())
            screen.finish()
        }
    }

    @Test fun turningItOffStopsTheKeeperAndRemovesTheAddressWithoutRoot() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        HotspotExtraAddressSettings.setEnabled(context, true)
        val screen = openConnection()
        assertEquals(listOf(defaultAddress to false), KeeperProbe.starts) // started when the screen resumed
        KeeperProbe.running = true

        extraAddressSwitch(screen).performClick()

        assertFalse(HotspotExtraAddressSettings.enabled(context))
        assertEquals(listOf(true), KeeperProbe.stops)
        assertFalse(extraAddressSwitch(screen).isChecked)
        assertTrue(RootShellProbe.scripts.isEmpty())
    }

    @Test fun leavingPhoneModeStopsTheKeeperAndRemovesTheAddress() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        HotspotExtraAddressSettings.setEnabled(context, true)
        val screen = openConnection()
        KeeperProbe.running = true

        descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_phone_browser_mode) }.performClick()

        assertEquals(CarPlayRunMode.HEAD_UNIT, AirPlayPersistence.loadRunMode(context))
        assertEquals(listOf(true), KeeperProbe.stops)
        assertTrue(HotspotExtraAddressSettings.enabled(context)) // back in phone mode it starts again
        assertFalse(screen.getString(R.string.settings_hotspot_extra_address) in texts(screen).map { it.text })
    }

    @Test fun leavingPhoneModeDuringAPhoneSessionKeepsTheAddressUntilThatSessionEnds() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        HotspotExtraAddressSettings.setEnabled(context, true)
        val screen = openConnection()
        val session = storeSession(phoneBrowser = true)
        KeeperProbe.reset()
        KeeperProbe.running = true

        phoneModeSwitch(screen).performClick()

        // The run mode applies at the next connection, so the car's page keeps its address for this session.
        assertEquals(CarPlayRunMode.HEAD_UNIT, AirPlayPersistence.loadRunMode(context))
        assertTrue(PendingReconnect.isPending(session))
        assertTrue(KeeperProbe.stops.isEmpty())
        assertTrue(KeeperProbe.starts.all { it == (defaultAddress to false) }) // at most a refresh
        val service = Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)
        assertEquals(defaultAddress to false, KeeperProbe.starts.last())
        assertTrue(KeeperProbe.stops.isEmpty())

        // That session ends: now the address goes, once.
        CarPlayBackgroundSession.clear(session)
        service.destroy()
        assertEquals(listOf(true), KeeperProbe.stops)
        assertTrue(RootShellProbe.scripts.isEmpty())
    }

    @Test fun aHeadUnitSessionStartsNoKeeperUntilThePhoneModeConnection() {
        HotspotExtraAddressSettings.setEnabled(context, true)
        val session = storeSession(phoneBrowser = false) // connected in head-unit mode
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER) // picked during that session
        openConnection()
        val headUnit = Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)

        assertTrue(KeeperProbe.starts.isEmpty())
        assertTrue(RootShellProbe.scripts.isEmpty())

        CarPlayBackgroundSession.clear(session)
        headUnit.destroy()
        storeSession(phoneBrowser = true) // the next connection uses phone + browser mode
        Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)
        assertEquals(listOf(defaultAddress to false), KeeperProbe.starts)
    }

    @Test fun theAddressRowAcceptsOnlyTheSharedAddressSpaceAndMovesTheKeeper() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        HotspotExtraAddressSettings.setEnabled(context, true)
        val screen = openConnection()
        val prefix = screen.getString(R.string.settings_hotspot_extra_address_value) + " · "
        KeeperProbe.reset()

        texts(screen).filterIsInstance<Button>().single { it.text.toString() == prefix + HotspotExtraAddress.DEFAULT }
            .performClick()
        shadowOf(Looper.getMainLooper()).idle() // delivers the dialog's show callback
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        assertEquals(HotspotExtraAddress.DEFAULT, input.text.toString())
        for (invalid in listOf("100.128.0.1", "10.0.0.1", "1.2.3", "tesla.local", "100.109.220.253;reboot", "")) {
            input.setText(invalid)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(invalid, screen.getString(R.string.settings_hotspot_extra_address_invalid), input.error?.toString())
            assertTrue(invalid, dialog.isShowing)
        }
        assertEquals(defaultAddress, HotspotExtraAddressSettings.address(context))
        assertTrue(KeeperProbe.starts.isEmpty())

        input.setText(" 100.64.1.2 ")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()

        assertFalse(dialog.isShowing)
        assertEquals(ip("100.64.1.2"), HotspotExtraAddressSettings.address(context))
        assertEquals(listOf(ip("100.64.1.2") to false), KeeperProbe.starts)
        assertTrue(texts(screen).any { it.text.toString() == prefix + "100.64.1.2" })
        assertTrue(shown(screen).any { it.contains("100.64.1.2") && it.startsWith("Needs root.") })
        assertTrue(RootShellProbe.scripts.isEmpty())
    }

    @Test fun theKeeperLineSaysWhatTheKeeperDoes() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        HotspotExtraAddressSettings.setEnabled(context, true)
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

    @Test fun theSettingIsOffByDefaultAndTheDiagnosticLineHoldsNoAddress() {
        assertFalse(HotspotExtraAddressSettings.enabled(context))
        assertEquals(defaultAddress, HotspotExtraAddressSettings.address(context))
        context.getSharedPreferences("tiplay_hotspot_address", 0).edit()
            .putString("hotspot_extra_address", "10.0.0.1").commit()
        assertEquals(defaultAddress, HotspotExtraAddressSettings.address(context))
        HotspotExtraAddressSettings.saveAddress(context, ip("100.64.1.2"))
        HotspotExtraAddressSettings.setEnabled(context, true)

        val line = HotspotExtraAddressSettings.diagnosticLine(context)

        assertTrue(line, line.contains("enabled=true"))
        assertFalse(line, line.contains("100.64.1.2"))
        assertEquals("100.64.1.2",
            context.getSharedPreferences("tiplay_hotspot_address", 0).getString("hotspot_extra_address", null))
    }

    @Test fun theSessionServiceStartsTheKeeperOnlyInPhoneModeAndKeepsTheAddressWhenItStops() {
        HotspotExtraAddressSettings.setEnabled(context, true)
        val headUnit = Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)
        headUnit.destroy()
        assertTrue(KeeperProbe.starts.isEmpty())

        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        KeeperProbe.reset()
        val phone = Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)
        assertEquals(listOf(defaultAddress to false), KeeperProbe.starts)
        phone.destroy()
        assertEquals(listOf(false), KeeperProbe.stops)

        KeeperProbe.reset()
        val stop = Robolectric.buildService(DiPlaySessionService::class.java,
            Intent().setAction(DiPlaySessionService.ACTION_STOP)).create().startCommand(0, 1)
        stop.destroy()
        assertTrue(KeeperProbe.starts.isEmpty())
        assertEquals(listOf(false), KeeperProbe.stops)
        assertTrue(RootShellProbe.scripts.isEmpty())
    }

    @Implements(RootShell::class, isInAndroidSdk = false)
    class RootShellProbe {
        @Implementation fun run(script: String, timeoutMillis: Long): RootShell.Result {
            scripts += script
            timeouts += timeoutMillis
            threads += Thread.currentThread()
            return answer
        }

        companion object {
            val scripts = CopyOnWriteArrayList<String>()
            val timeouts = CopyOnWriteArrayList<Long>()
            val threads = CopyOnWriteArrayList<Thread>()
            @Volatile var answer: RootShell.Result = RootShell.Result.Unavailable
            fun reset() { scripts.clear(); timeouts.clear(); threads.clear(); answer = RootShell.Result.Unavailable }
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
        @Implementation fun start(context: Context, address: Inet4Address, eligible: () -> Boolean, retryRootDenied: Boolean) {
            starts += address to retryRootDenied
        }

        @Implementation fun stop(removeAddress: Boolean) { stops += removeAddress }

        @Implementation fun getRunning(): Boolean = running

        @Implementation fun getState(): HotspotExtraAddressKeeper.State = state

        companion object {
            val starts = CopyOnWriteArrayList<Pair<Inet4Address, Boolean>>()
            val stops = CopyOnWriteArrayList<Boolean>()
            @Volatile var running = false
            @Volatile var state: HotspotExtraAddressKeeper.State = HotspotExtraAddressKeeper.State.Off
            fun reset() {
                starts.clear(); stops.clear(); running = false; state = HotspotExtraAddressKeeper.State.Off
            }
        }
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

    private fun extraAddressSwitch(screen: DiPlayActivity): Switch =
        descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_hotspot_extra_address) }

    private fun searchIndex(screen: DiPlayActivity) =
        ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(screen, "buildSettingsSearchIndex")

    private fun openConnection(): DiPlayActivity = openSettings().also { screen ->
        descendants(screen.window.decorView).first { candidate ->
            candidate.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(R.string.connection))
        }.performClick()
        finishProbes()
    }

    private fun visibleIn(screen: DiPlayActivity, category: Int): List<String> {
        descendants(screen.window.decorView).first { candidate ->
            candidate.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(category))
        }.performClick()
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
