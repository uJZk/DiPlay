package com.shilapi.xcertplay

import com.shilapi.xcertplay.orchestration.CarPlayRunMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BrowserSettingsPolicyTest {
    @Test fun openingTheProductMigratesOldChoicesWithoutChangingManualCredentials() {
        val context = RuntimeEnvironment.getApplication()
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.HEAD_UNIT)
        AirPlayPersistence.saveCarBluetoothAudio(context, CarBluetoothAudio.OFF)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.SCHEDULE)
        AirPlayPersistence.saveLocationReportingEnabled(context, true)
        AirPlayPersistence.saveManualHotspotSsid(context, "Manual phone hotspot")
        AirPlayPersistence.saveManualHotspotPassphrase(context, "manual123")
        SplitScreenSettings.setEnabled(context, false)
        BrowserSettingsPolicy.activate(context)
        assertEquals(CarPlayRunMode.PHONE_BROWSER, AirPlayPersistence.loadRunMode(context))
        assertEquals(CarBluetoothAudio.ON, AirPlayPersistence.loadCarBluetoothAudio(context))
        assertEquals(CarPlayNightMode.SYSTEM, AirPlayPersistence.loadCarPlayNightMode(context))
        assertTrue(SplitScreenSettings.enabled(context))
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
        assertEquals("Manual phone hotspot", AirPlayPersistence.loadManualHotspotSsid(context))
        assertEquals("manual123", AirPlayPersistence.loadManualHotspotPassphrase(context))
    }
}
