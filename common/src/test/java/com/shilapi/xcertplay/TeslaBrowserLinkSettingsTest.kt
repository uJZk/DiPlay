package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.HotspotAddresses
import com.shilapi.xcertplay.network.HotspotExtraAddressKeeper
import com.shilapi.xcertplay.orchestration.CarPlayRunMode
import java.net.BindException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp",
    shadows = [TeslaBrowserLinkSettingsTest.HotspotProbe::class, TeslaBrowserLinkSettingsTest.KeeperProbe::class])
class TeslaBrowserLinkSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null
    private val servers = AtomicInteger()
    private val failBinds = AtomicInteger()

    @Before fun setUp() {
        TeslaBrowserLink.resetForTest()
        HotspotProbe.reset()
        KeeperProbe.reset()
        TeslaBrowserLink.backoffMillis = { 10L }
        TeslaBrowserLink.serverFactory = TeslaBrowserLink.ServerFactory { _, _, _ ->
            servers.incrementAndGet()
            object : TeslaBrowserLink.Server {
                override fun start(): Result<Int> = if (failBinds.getAndDecrement() > 0) {
                    Result.failure(BindException("Address already in use"))
                } else {
                    Result.success(TeslaBrowserLink.PORT)
                }
                override fun stop() = Unit
            }
        }
    }

    @After fun tearDown() {
        activity?.finish()
        TeslaBrowserLink.resetForTest()
        TeslaBrowserLink.serverFactory = TiPlayTestApplication.noSocketServers
        TeslaBrowserLink.backoffMillis = { failures -> minOf(30_000L, 1_000L shl minOf(failures, 5)) }
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        listOf("diplay", "xcertplay_airplay", "tiplay_hotspot_address", "tiplay_browser_link").forEach {
            context.getSharedPreferences(it, 0).edit().clear().commit()
        }
    }

    @Test fun theLinkRowsLiveInConnectionOnlyInPhoneMode() {
        val screen = openSettings()
        val code = TeslaBrowserLink.pairingCode(context)
        val page = screen.getString(R.string.settings_browser_page_address) + " · "
        val pairing = screen.getString(R.string.settings_browser_pairing_code) + " · "
        val overview = texts(screen).map { it.text.toString() }.toList()
        val pages = listOf(R.string.connection, R.string.settings_display, R.string.audio, R.string.settings_navigation,
            R.string.settings_vehicle, R.string.diagnostics, R.string.settings_advanced).associateWith { visibleIn(screen, it) }

        val connection = pages.getValue(R.string.connection)
        assertTrue(connection.contains(page + TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS))
        assertTrue(connection.contains(pairing + code))
        assertTrue(connection.contains("https://ujzk.github.io/DiPlay/play/#c=$code"))
        (pages - R.string.connection).values.plusElement(overview).forEach { texts ->
            assertFalse(texts.any { it.startsWith(page) || it.startsWith(pairing) || it.contains("#c=") })
        }
        val index = searchIndex(screen)
        assertEquals(SettingsCategory.CONNECTION,
            index.single { it.title == screen.getString(R.string.settings_browser_page_address) }.category)
        assertEquals(SettingsCategory.CONNECTION,
            index.single { it.title == screen.getString(R.string.settings_browser_pairing_code) }.category)
        assertFalse("Copy buttons are not search results",
            index.any { it.title == screen.getString(R.string.settings_browser_link_copy) })
    }

    @Test fun aHeadUnitHasNoLinkRowsAndStartsNoLink() {
        useHeadUnitMode(context)
        val screen = openSettings()
        val connection = visibleIn(screen, R.string.connection)
        assertFalse(connection.any { it.startsWith(screen.getString(R.string.settings_browser_page_address)) })
        assertFalse(searchIndex(screen).any { it.title == screen.getString(R.string.settings_browser_pairing_code) })
        assertEquals(0, servers.get())
        assertFalse(TeslaBrowserLink.active)
        assertFalse(TeslaBrowserLink.hasPairingCode(context))
    }

    @Test fun openingTiPlayStartsTheLinkAndTheRunModeSwitchStopsIt() {
        val screen = openSettings()
        assertTrue(TeslaBrowserLink.awaitIdle())
        assertEquals(TeslaBrowserLink.State.Listening(8080), TeslaBrowserLink.state)
        assertEquals(1, servers.get())

        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.CONNECTION)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        descendants(screen.window.decorView).filterIsInstance<android.widget.Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_phone_browser_mode) }.performClick()
        assertTrue(TeslaBrowserLink.awaitIdle())
        assertEquals(CarPlayRunMode.HEAD_UNIT, AirPlayPersistence.loadRunMode(context))
        assertEquals(TeslaBrowserLink.State.Stopped, TeslaBrowserLink.state)
    }

    @Test fun buildingTheSearchIndexStartsNoLinkMakesNoCodeAndProbesNoNetwork() {
        val screen = openSettings()
        TeslaBrowserLink.resetForTest() // what opening TiPlay started is not part of this check
        context.getSharedPreferences("tiplay_browser_link", 0).edit().clear().commit()
        finishProbes()
        HotspotProbe.reset()
        servers.set(0)

        val index = searchIndex(screen)

        assertTrue(index.any { it.title == screen.getString(R.string.settings_browser_pairing_code) })
        assertTrue(TeslaBrowserLink.awaitIdle())
        assertEquals("Building search metadata must not start the server", 0, servers.get())
        assertFalse(TeslaBrowserLink.active)
        assertFalse("Building search metadata must not create a pairing code", TeslaBrowserLink.hasPairingCode(context))
        assertTrue("Building search metadata must not enumerate interfaces", HotspotProbe.threads.isEmpty())
    }

    @Test fun theStatusLineFollowsTheLink() {
        val screen = openConnection()
        val listening = screen.getString(R.string.settings_browser_link_listening, 8080)
        assertTrue(shown(screen).contains(listening))

        failBinds.set(1_000)
        TeslaBrowserLink.newPairingCode(context) // restarts the server, which now finds the port taken
        assertTrue(TeslaBrowserLink.awaitIdle())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TeslaBrowserLink.State.PortInUse, TeslaBrowserLink.state)
        assertTrue(shown(screen).contains(screen.getString(R.string.settings_browser_link_port_in_use, 8080)))

        failBinds.set(0)
        val deadline = System.currentTimeMillis() + 5_000
        while (TeslaBrowserLink.state != TeslaBrowserLink.State.Listening(8080) && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(TeslaBrowserLink.awaitIdle()) // the listeners run on the link's thread right after the change
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shown(screen).contains(listening))
    }

    @Test fun theLinksCarryTheCodeAndTheExtraAddressOnlyWhenItDiffers() {
        HotspotProbe.result = HotspotAddresses.Current("wlan2", listOf(ip("10.176.81.135")))
        var screen = openConnection()
        val code = TeslaBrowserLink.pairingCode(context)
        assertTrue(shown(screen).contains("https://ujzk.github.io/DiPlay/play/#c=$code"))
        assertTrue(shown(screen).contains("http://10.176.81.135:8080/play/#c=$code"))
        assertTrue("The probe ran off the main thread", HotspotProbe.threads.none { it == Looper.getMainLooper().thread })

        // The extra address at its default changes nothing in the car link.
        HotspotExtraAddressSettings.setEnabled(context, true)
        rerender(screen)
        assertTrue(shown(screen).contains("https://ujzk.github.io/DiPlay/play/#c=$code"))

        HotspotExtraAddressSettings.saveAddress(context, ip("100.64.7.9"))
        KeeperProbe.state = HotspotExtraAddressKeeper.State.Added("wlan2")
        rerender(screen)
        assertTrue(shown(screen).contains("https://ujzk.github.io/DiPlay/play/#c=$code&h=100.64.7.9:8080"))
        assertTrue("Once added, the phone's page uses the extra address",
            shown(screen).contains("http://100.64.7.9:8080/play/#c=$code"))

        activity?.finish()
        HotspotProbe.result = null
        HotspotExtraAddressSettings.setEnabled(context, false)
        screen = openConnection()
        assertEquals("The hotspot line and the phone's page link both say so", 2,
            shown(screen).count { it == screen.getString(R.string.settings_hotspot_not_found) })
    }

    @Test fun copyPutsTheLinkOnTheClipboard() {
        HotspotProbe.result = HotspotAddresses.Current("wlan2", listOf(ip("10.176.81.135")))
        val screen = openConnection()
        val code = TeslaBrowserLink.pairingCode(context)
        val copies = texts(screen).filterIsInstance<Button>()
            .filter { it.text.toString() == screen.getString(R.string.settings_browser_link_copy) }.toList()
        assertEquals(2, copies.size)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        copies[0].performClick()
        assertEquals("https://ujzk.github.io/DiPlay/play/#c=$code", clipboard.primaryClip!!.getItemAt(0).text.toString())
        copies[1].performClick()
        assertEquals("http://10.176.81.135:8080/play/#c=$code", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test fun thePageAddressMustBeHttps() {
        val screen = openConnection()
        val prefix = screen.getString(R.string.settings_browser_page_address) + " · "
        button(screen, prefix + TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val input = descendants(dialog.window!!.decorView).filterIsInstance<EditText>().single()
        assertEquals(TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS, input.text.toString())
        for (invalid in listOf("http://car.example/play/", "car.example", "https://car.example/#c=1", "")) {
            input.setText(invalid)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertEquals(invalid, screen.getString(R.string.settings_browser_page_address_invalid), input.error?.toString())
            assertTrue(dialog.isShowing)
        }
        assertEquals(TeslaBrowserPageLinks.DEFAULT_PAGE_ADDRESS, TeslaBrowserLink.pageAddress(context))

        input.setText(" https://car.example/tiplay/ ")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishProbes()
        assertFalse(dialog.isShowing)
        assertEquals("https://car.example/tiplay/", TeslaBrowserLink.pageAddress(context))
        val code = TeslaBrowserLink.pairingCode(context)
        assertTrue(shown(screen).contains(prefix + "https://car.example/tiplay/"))
        assertTrue(shown(screen).contains("https://car.example/tiplay/#c=$code"))
    }

    @Test fun aNewPairingCodeReplacesTheOldOneEverywhere() {
        val screen = openConnection()
        val old = TeslaBrowserLink.pairingCode(context)
        val prefix = screen.getString(R.string.settings_browser_pairing_code) + " · "
        button(screen, prefix + old).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(shadowOf(dialog).message.toString().contains(old))
        assertEquals(screen.getString(R.string.settings_browser_pairing_code_new),
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        finishProbes()

        val new = TeslaBrowserLink.pairingCode(context)
        assertNotEquals(old, new) // one in a million: SecureRandom picked the same six digits
        assertTrue(shown(screen).contains(prefix + new))
        assertTrue(shown(screen).contains("https://ujzk.github.io/DiPlay/play/#c=$new"))
        assertFalse(shown(screen).any { it.contains("#c=$old") })
    }

    @Config(shadows = [DeniedLocalNetwork::class])
    @Test fun aDeniedLocalNetworkPermissionIsAskedForUntilAndroidStopsAskingThenTheAppSettingsOpen() {
        val screen = openConnection()
        assertTrue(shown(screen).contains(screen.getString(R.string.settings_browser_local_network_denied)))
        val allow = screen.getString(R.string.settings_browser_local_network_allow)
        button(screen, allow).performClick()
        val first = shadowOf(screen).lastRequestedPermission
        assertEquals(listOf(LocalNetworkPermission.PERMISSION), first?.requestedPermissions?.toList())

        // After a first denial Android still shows its dialog.
        shadowOf(context.packageManager).setShouldShowRequestPermissionRationale(LocalNetworkPermission.PERMISSION, true)
        button(screen, allow).performClick()
        val second = shadowOf(screen).lastRequestedPermission
        assertTrue("Asked again", second !== first)
        val asked = generateSequence { shadowOf(screen).nextStartedActivity }.map { it.action }.toList()
        assertFalse("No settings page while Android still asks: $asked",
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS in asked)

        // Denied for good: a request would return at once, so the button opens the app's settings page instead.
        shadowOf(context.packageManager).setShouldShowRequestPermissionRationale(LocalNetworkPermission.PERMISSION, false)
        button(screen, allow).performClick()
        assertSame("No request without a dialog", second, shadowOf(screen).lastRequestedPermission)
        val settings = shadowOf(screen).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, settings.action)
        assertEquals("package:${context.packageName}", settings.dataString)
    }

    /** Android 17 with the permission denied; Robolectric's platforms are older. */
    @Implements(LocalNetworkPermission::class, isInAndroidSdk = false)
    internal class DeniedLocalNetwork {
        @Implementation fun state(context: Context): LocalNetworkPermission.State = LocalNetworkPermission.State.DENIED
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

    /** The keeper runs `su`; these tests only need its state. */
    @Implements(HotspotExtraAddressKeeper::class, isInAndroidSdk = false)
    class KeeperProbe {
        @Implementation fun start(context: Context, address: Inet4Address, eligible: () -> Boolean, retryRootDenied: Boolean) = Unit

        @Implementation fun stop(removeAddress: Boolean) = Unit

        @Implementation fun getRunning(): Boolean = state != HotspotExtraAddressKeeper.State.Off

        @Implementation fun getState(): HotspotExtraAddressKeeper.State = state

        companion object {
            @Volatile var state: HotspotExtraAddressKeeper.State = HotspotExtraAddressKeeper.State.Off
            fun reset() { state = HotspotExtraAddressKeeper.State.Off }
        }
    }

    private fun finishProbes() {
        HotspotProbe.threads.forEach { it.join(3_000) }
        shadowOf(Looper.getMainLooper()).idle()
        // A probe can start while the main looper idles (a render after a dialog).
        HotspotProbe.threads.forEach { it.join(3_000) }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun rerender(screen: DiPlayActivity) {
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        finishProbes()
    }

    private fun searchIndex(screen: DiPlayActivity) =
        ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(screen, "buildSettingsSearchIndex")

    private fun openSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { activity = it }

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

    private fun button(screen: DiPlayActivity, text: String): Button =
        texts(screen).filterIsInstance<Button>().single { it.text.toString() == text }

    private fun shown(screen: DiPlayActivity): List<String> =
        texts(screen).filter { it.isShown }.map { it.text.toString() }.toList()

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun ip(value: String) = InetAddress.getByName(value) as Inet4Address
}
