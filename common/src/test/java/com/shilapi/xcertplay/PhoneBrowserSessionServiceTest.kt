package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.CarPlayRunMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/** Integration E3: in phone + browser mode the session service keeps the CPU and Wi-Fi awake, and nothing else does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneBrowserSessionServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val wifi get() = shadowOf(context.getSystemService(Context.WIFI_SERVICE) as WifiManager)

    @Before fun setUp() {
        TeslaBrowserLink.resetForTest()
        ShadowPowerManager.clearWakeLocks()
    }

    @After fun tearDown() {
        TeslaBrowserLink.resetForTest()
        CarPlayBackgroundSession.clear()
        context.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        context.getSharedPreferences("tiplay_browser_link", 0).edit().clear().commit()
    }

    private fun started(): ServiceController<DiPlaySessionService> =
        Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)

    @Test fun phoneModeHoldsAPartialWakeLockAndALowLatencyWifiLockUntilTheServiceEnds() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        val service = started()
        val wake = ShadowPowerManager.getLatestWakeLock()
        assertTrue(wake.isHeld)
        assertEquals("TiPlay:browser-link", shadowOf(wake).tag)
        assertEquals(1, wifi.activeLockCount)
        assertTrue(locks(service).held)
        assertTrue("The service starts the browser link", TeslaBrowserLink.active)

        // Each new connection starts the service again; the locks do not stack.
        service.startCommand(0, 2)
        assertEquals(1, wifi.activeLockCount)
        assertTrue(wake.isHeld)

        service.destroy()
        assertFalse(wake.isHeld)
        assertEquals(0, wifi.activeLockCount)
    }

    @Test fun disconnectAndRemovingTheTaskReleaseTheLocks() {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        val disconnected = started()
        val wake = ShadowPowerManager.getLatestWakeLock()
        disconnected.withIntent(Intent(context, DiPlaySessionService::class.java).setAction(DiPlaySessionService.ACTION_STOP))
            .startCommand(0, 2)
        assertFalse(wake.isHeld)
        assertEquals(0, wifi.activeLockCount)
        disconnected.destroy()

        ShadowPowerManager.clearWakeLocks()
        val removed = started()
        val second = ShadowPowerManager.getLatestWakeLock()
        assertTrue(second.isHeld)
        removed.get().onTaskRemoved(Intent())
        assertFalse(second.isHeld)
        assertEquals(0, wifi.activeLockCount)
        removed.destroy()
    }

    @Test fun headUnitModeHoldsNoLockAndStartsNoLink() {
        useHeadUnitMode(context)
        val service = started()
        assertNull(ShadowPowerManager.getLatestWakeLock())
        assertEquals(0, wifi.activeLockCount)
        assertFalse(TeslaBrowserLink.active)
        assertFalse(locks(service).held)
        service.destroy()
    }

    @Test fun wifiStaysInLowLatencyModeOrHighPerformanceBeforeAndroid10() {
        assertEquals(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, PhoneBrowserLocks.wifiMode(29))
        assertEquals(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, PhoneBrowserLocks.wifiMode(37))
        @Suppress("DEPRECATION")
        assertEquals(WifiManager.WIFI_MODE_FULL_HIGH_PERF, PhoneBrowserLocks.wifiMode(28))
    }

    private fun locks(service: ServiceController<DiPlaySessionService>): PhoneBrowserLocks =
        service.get().javaClass.getDeclaredMethod("getPhoneBrowserLocks").apply { isAccessible = true }
            .invoke(service.get()) as PhoneBrowserLocks
}
