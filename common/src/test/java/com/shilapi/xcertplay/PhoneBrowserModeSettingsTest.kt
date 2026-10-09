package com.shilapi.xcertplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRunMode
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class PhoneBrowserModeSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    // Every head-unit control the phone + browser mode hides, wherever its category is.
    private val headUnitControls = listOf(
        R.string.settings_wheel_keys,
        R.string.byd_adb_features,
        R.string.auto_car_hotspot_title,
        R.string.carplay_map_on_instrument_cluster_experimental,
        R.string.byd_navigation,
        R.string.advanced_vehicle_data,
        R.string.open_after_the_car_starts,
        R.string.usb_auto_confirm_title,
        R.string.btn_auto_apply_permissions,
    )

    @After fun tearDown() {
        activity?.finish()
        CarPlayBackgroundSession.clear()
        PendingReconnect.clear()
        context.getSharedPreferences("diplay", 0).edit().clear().commit()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
    }

    @Test fun phoneBrowserIsTheDefaultAndUnknownRunMode() {
        assertEquals(CarPlayRunMode.PHONE_BROWSER, AirPlayPersistence.loadRunMode(context))
        assertTrue(AirPlayPersistence.isPhoneBrowserMode(context))
        context.getSharedPreferences("xcertplay_airplay", 0).edit().putString("run_mode", "TABLET").commit()

        assertEquals(CarPlayRunMode.PHONE_BROWSER, AirPlayPersistence.loadRunMode(context))
        assertTrue(AirPlayPersistence.isPhoneBrowserMode(context))

        // A head unit chosen in Settings stays chosen.
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.HEAD_UNIT)
        assertEquals(CarPlayRunMode.HEAD_UNIT, AirPlayPersistence.loadRunMode(context))
        assertFalse(AirPlayPersistence.isPhoneBrowserMode(context))
    }

    // The default mode is the switch's "on"; turning it off is the way back to head-unit mode.
    @Test fun aFreshInstallShowsTheRunModeSwitchOnInTheTeslaBrowserCard() {
        val screen = openSettings()
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.CONNECTION)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")

        assertTrue(runModeSwitch(screen).isChecked)
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_tesla_browser) })
        assertTrue(texts(screen).any { it.text == screen.getString(R.string.settings_phone_browser_mode_description) })
        // Only the card says experimental; the switch is the run mode itself.
        assertFalse(screen.getString(R.string.settings_phone_browser_mode).contains("experimental"))
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun theCarButtonPreviewShowsWhatTheNextConnectionSends() {
        val screen = openSettings()
        fun carButton(): Pair<String, android.graphics.Bitmap> {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.VEHICLE)
            ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
            val name = descendants(screen.window.decorView).filterIsInstance<android.widget.Button>()
                .single { it.text.startsWith(screen.getString(R.string.car_button_name) + " · ") }.text.toString()
            val card = ReflectionHelpers.getField<ViewGroup>(screen, "carButtonCard")
            val icon = descendants(card).filterIsInstance<android.widget.ImageView>()
                .mapNotNull { (it.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap }.single()
            return name.substringAfter(" · ") to icon
        }
        val phoneIcon = requireNotNull(CarButtonDefaults.phoneBrowserIcon(context))
        val bydIcon = android.graphics.BitmapFactory.decodeResource(context.resources, R.raw.ic_car_home)

        val (phoneName, phonePreview) = carButton()
        assertEquals("Tesla", phoneName)
        assertTrue(phonePreview.sameAs(phoneIcon))
        assertFalse(phonePreview.sameAs(bydIcon))

        useHeadUnitMode()
        val (headUnitName, headUnitPreview) = carButton()
        assertEquals(AirPlayPersistence.DEFAULT_OEM_LABEL, headUnitName)
        assertTrue(headUnitPreview.sameAs(bydIcon))

        // A name the driver chose is kept in both modes.
        AirPlayPersistence.saveOemLabel(context, "My car")
        assertEquals("My car", carButton().first)
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        assertEquals("My car", carButton().first)
    }

    @Test fun searchFindsTheHeadUnitControlsOnlyOutsidePhoneMode() {
        useHeadUnitMode()
        installBydSettingsPackage()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
        val screen = openSettings()
        fun titles() = ReflectionHelpers.callInstanceMethod<List<DiPlayActivity.SettingsSearchResult>>(
            screen, "buildSettingsSearchIndex").map { it.title }

        val headUnit = titles()
        headUnitControls.forEach { assertTrue(screen.getString(it), screen.getString(it) in headUnit) }
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        val phone = titles()

        headUnitControls.forEach { assertFalse(screen.getString(it), screen.getString(it) in phone) }
        assertTrue(screen.getString(R.string.settings_phone_browser_mode) in phone)
        assertTrue(screen.getString(R.string.settings_tesla_browser) in phone)
        // Car Bluetooth sound is the other way round: only a car browser offers it.
        assertFalse(screen.getString(R.string.settings_car_bluetooth_audio) in headUnit)
        assertTrue(screen.getString(R.string.settings_car_bluetooth_audio) in phone)
    }

    @Test fun phoneModeHidesTheHeadUnitCardsWhereDriversLook() {
        installBydSettingsPackage()
        // Keeps the BYD ADB card from probing ADB while the Connection page renders.
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        val screen = openSettings()

        val connection = visibleIn(screen, R.string.connection)
        val navigation = visibleIn(screen, R.string.settings_navigation)
        val vehicle = visibleIn(screen, R.string.settings_vehicle)
        val advanced = visibleIn(screen, R.string.settings_advanced)

        assertTrue(screen.getString(R.string.settings_tesla_browser) in connection)
        assertTrue(screen.getString(R.string.connect_when_diplay_opens) in connection)
        assertFalse(screen.getString(R.string.open_after_the_car_starts) in connection)
        assertFalse(screen.getString(R.string.usb_auto_confirm_title) in connection)
        assertFalse(screen.getString(R.string.btn_auto_apply_permissions) in connection)
        assertTrue(screen.getString(R.string.location) in navigation)
        assertFalse(screen.getString(R.string.byd_navigation) in navigation)
        assertFalse(screen.getString(R.string.settings_byd_navigation_unavailable) in navigation)
        assertTrue(screen.getString(R.string.car_button_in_carplay) in vehicle)
        assertFalse(screen.getString(R.string.settings_wheel_keys) in vehicle)
        assertTrue(screen.getString(R.string.settings_advanced_caution_title) in advanced)
        assertFalse(screen.getString(R.string.carplay_map_on_instrument_cluster_experimental) in advanced)
        assertFalse(screen.getString(R.string.advanced_vehicle_data) in advanced)
    }

    @Test fun headUnitModeKeepsTheHeadUnitCardsWhereDriversLook() {
        useHeadUnitMode()
        installBydSettingsPackage()
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        val screen = openSettings()

        val connection = visibleIn(screen, R.string.connection)
        val navigation = visibleIn(screen, R.string.settings_navigation)
        val vehicle = visibleIn(screen, R.string.settings_vehicle)
        val advanced = visibleIn(screen, R.string.settings_advanced)

        assertTrue(screen.getString(R.string.settings_tesla_browser) in connection)
        assertTrue(screen.getString(R.string.open_after_the_car_starts) in connection)
        assertTrue(screen.getString(R.string.usb_auto_confirm_title) in connection)
        assertTrue(screen.getString(R.string.byd_navigation) in navigation)
        assertTrue(screen.getString(R.string.settings_wheel_keys) in vehicle)
        assertTrue(screen.getString(R.string.carplay_map_on_instrument_cluster_experimental) in advanced)
        assertTrue(screen.getString(R.string.advanced_vehicle_data) in advanced)
    }

    @Test
    @Config(sdk = [28, 33])
    fun switchingTheRunModeMarksTheActiveSessionForReconnect() {
        val screen = openSettings()
        val session = mock(CarPlayController::class.java)
        var stops = 0
        CarPlayBackgroundSession.store(session, mock(AndroidMediaSink::class.java), 800, 480, Any(),
            CarPlaySessionDisplay(800, 480, Surface.ROTATION_0, false, false, 800, 480)) { stops++ }
        CarPlayBackgroundSession.active = true
        try {
            ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.CONNECTION)
            listOf(CarPlayRunMode.HEAD_UNIT, CarPlayRunMode.PHONE_BROWSER).forEach { mode ->
                PendingReconnect.clear()
                ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
                val setting = runModeSwitch(screen)
                assertEquals(mode == CarPlayRunMode.HEAD_UNIT, setting.isChecked)
                setting.performClick()

                assertEquals(mode, AirPlayPersistence.loadRunMode(context))
                assertEquals(mode == CarPlayRunMode.PHONE_BROWSER, runModeSwitch(screen).isChecked)
                // The card list follows the mode at once; CarPlay only picks it up at the next connection.
                assertEquals(mode == CarPlayRunMode.HEAD_UNIT,
                    texts(screen).any { it.text == screen.getString(R.string.open_after_the_car_starts) })
                assertTrue(PendingReconnect.isPending(session))
                assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "reconnectBar").visibility)
                assertSame(session, CarPlayBackgroundSession.snapshot()?.controller)
                assertEquals(0, stops)
                assertEquals(null, shadowOf(screen).nextStartedActivity)
            }
        } finally {
            CarPlayBackgroundSession.clear()
            PendingReconnect.clear()
        }
    }

    private fun runModeSwitch(screen: DiPlayActivity): Switch =
        descendants(screen.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == screen.getString(R.string.settings_phone_browser_mode) }

    private fun visibleIn(screen: DiPlayActivity, category: Int): List<CharSequence> {
        descendants(screen.window.decorView).first { candidate ->
            candidate.contentDescription == screen.getString(R.string.settings_open_category, screen.getString(category))
        }.performClick()
        return texts(screen).map { it.text }.toList().also { screen.onBackPressedDispatcher.onBackPressed() }
    }

    private fun installBydSettingsPackage() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.byd.carsettings"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.byd.carsettings"
                flags = ApplicationInfo.FLAG_SYSTEM
            }
        })
    }

    private fun openSettings(): DiPlayActivity = Robolectric.buildActivity(
        DiPlayActivity::class.java,
        Intent(context, DiPlayActivity::class.java).putExtra("page", "settings"),
    ).setup().get().also { activity = it }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
