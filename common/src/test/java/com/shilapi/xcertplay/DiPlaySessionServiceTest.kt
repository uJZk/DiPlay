package com.shilapi.xcertplay

import android.Manifest
import android.content.Intent
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiPlaySessionServiceTest {
    @Test fun carBluetoothSoundLeavesTheMicrophoneOutOfTheForegroundService() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        for ((mode, microphone) in listOf(
            CarBluetoothAudio.OFF to true,
            CarBluetoothAudio.ON to false,
            CarBluetoothAudio.ALTERNATIVE to false,
        )) {
            AirPlayPersistence.saveCarBluetoothAudio(app, mode)
            val controller = Robolectric.buildService(DiPlaySessionService::class.java, Intent()).create().startCommand(0, 1)
            try {
                val types = controller.get().foregroundServiceType
                assertEquals(mode.name, microphone, types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0)
                assertTrue(mode.name, types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE != 0)
            } finally {
                controller.destroy()
            }
        }
    }
}
