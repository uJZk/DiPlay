package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.CarPlayRunMode

/** TiPlay is a browser receiver; head-unit configuration is retained only for upstream compatibility. */
internal object BrowserSettingsPolicy {
    /** Apply the product profile on entry. A running session keeps its negotiated configuration. */
    fun activate(context: Context) {
        AirPlayPersistence.saveRunMode(context, CarPlayRunMode.PHONE_BROWSER)
        AirPlayPersistence.saveCarBluetoothAudio(context, CarBluetoothAudio.ON)
        AirPlayPersistence.saveCarPlayNightMode(context, CarPlayNightMode.SYSTEM)
        AirPlayPersistence.saveLocationReportingEnabled(context, false)
        SplitScreenSettings.setEnabled(context, true)
    }
    val hiddenSections = setOf(
        SettingsSection.DISPLAY_AND_PERFORMANCE, SettingsSection.EXPERIMENTAL_DISPLAY, SettingsSection.ADVANCED_MEDIA,
        SettingsSection.AUDIO_ROUTING, SettingsSection.LOCATION, SettingsSection.WHEEL_KEYS,
        SettingsSection.BYD_ADB, SettingsSection.CLUSTER_MAP, SettingsSection.BYD_NAVIGATION,
    )
    fun shows(section: SettingsSection) = section !in hiddenSections
}
