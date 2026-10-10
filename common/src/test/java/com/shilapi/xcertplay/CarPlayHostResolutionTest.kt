package com.shilapi.xcertplay

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.shadows.MediaCodecInfoBuilder
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayHostResolutionTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        // The head unit's display options; phone + browser mode sizes the canvas from the car's browser instead.
        useHeadUnitMode(activity)
        set("airPlayIdentity", AirPlayIdentity.generate())
        set("hevcEnabled", false)
        ShadowMediaCodecList.reset()
    }

    @After fun tearDown() {
        (get("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (get("teardownExecutor") as ExecutorService).shutdownNow()
        (get("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        ShadowMediaCodecList.reset()
    }

    @Test fun unsupportedEnlargedResolutionFallsBackBeforeAdvertisingItToThePhone() {
        decoder(maxWidth = 1920, maxHeight = 1080)
        val display = config(160).main
        assertEquals(1920, display.widthPixels)
        assertEquals(990, display.heightPixels)
        assertEquals(100, get("displayScalePercent"))
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(activity))
    }

    @Test fun supportedEnlargedResolutionKeepsItsPrecisePercentAndPhysicalSize() {
        decoder(maxWidth = 3840, maxHeight = 2160)
        val native = config(100).main
        val display = config(160).main
        assertEquals(DisplayDiagnosticSnapshot.report(activity), 3072, display.widthPixels)
        assertEquals(1584, display.heightPixels)
        assertEquals(native.widthPhysicalMm, display.widthPhysicalMm)
        assertEquals(native.heightPhysicalMm, display.heightPhysicalMm)
        assertEquals(160, AirPlayPersistence.loadDisplayScalePercent(activity))
    }

    @Test fun decoderFrameRateAlsoGatesTheEnlargedStream() {
        decoder(maxWidth = 3840, maxHeight = 2160, maxFps = 30.0)
        assertEquals(1920, config(160).main.widthPixels)
    }

    @Test fun decoderAlignmentAlsoGatesTheEnlargedStream() {
        decoder(maxWidth = 3840, maxHeight = 2160, alignment = 16)
        val display = config(160, height = 978).main
        assertEquals(1920, display.widthPixels)
        assertEquals(978, display.heightPixels)
    }

    @Test fun failedSmallerUiCanvasCanKeepASupportedSupersampledResolution() {
        decoder(maxWidth = 3120, maxHeight = 1700)
        val display = config(150, uiPercent = 75).main
        assertEquals(DisplayDiagnosticSnapshot.report(activity), 2880, display.widthPixels)
        assertEquals(1486, display.heightPixels)
        assertEquals(100, get("uiScalePercent"))
        assertEquals(150, AirPlayPersistence.loadDisplayScalePercent(activity))
    }

    @Test fun capabilityCheckUsesTheActualCanvasIncludingLargerUiControls() {
        decoder(maxWidth = 2200, maxHeight = 1200)
        val display = config(130, uiPercent = 115).main
        assertEquals(DisplayDiagnosticSnapshot.report(activity), 2170, display.widthPixels)
        assertEquals(1120, display.heightPixels)
        assertEquals(130, get("displayScalePercent"))
        assertEquals(115, get("uiScalePercent"))
    }

    @Test fun supersamplingCannotBypassTheExistingFourKEnlargementLimit() {
        decoder(maxWidth = 8192, maxHeight = 8192)
        val display = config(160, width = 3840, height = 2160).main
        assertEquals(3840, display.widthPixels)
        assertEquals(2160, display.heightPixels)
        assertEquals(100, get("displayScalePercent"))
    }

    @Test fun phoneBrowserModeOffersNoDashboardStreamOrParkedVideo() {
        useHeadUnitMode(activity)
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        com.shilapi.xcertplay.hud.BydOutputSettings.setVideoWhileParked(activity, true)
        try {
            val headUnit = config(100)
            assertNotNull(headUnit.cluster)
            assertTrue(headUnit.videoInCar)

            AirPlayPersistence.saveRunMode(activity, com.shilapi.xcertplay.orchestration.CarPlayRunMode.PHONE_BROWSER)
            val phone = config(100)
            assertNull(phone.cluster)
            assertFalse(phone.videoInCar)
        } finally {
            MapMirrors.streamAspect = MapMirrors.PHYSICAL_STREAM_ASPECT
        }
    }

    @Test fun normalResolutionKeepsTheExistingStartupPathWithoutDecoderMetadata() {
        val display = config(100).main
        assertEquals(1920, display.widthPixels)
        assertEquals(990, display.heightPixels)
        assertEquals(100, get("displayScalePercent"))
    }

    @Test fun supersamplingDoesNotSilentlyFallBackToASoftwareDecoder() {
        decoder(maxWidth = 3840, maxHeight = 2160, hardware = false)
        val display = config(160).main
        assertEquals(1920, display.widthPixels)
        assertEquals(100, AirPlayPersistence.loadDisplayScalePercent(activity))
        assertTrue(DisplayDiagnosticSnapshot.report(activity).contains("software_decoder"))
    }

    @Test fun carBluetoothAudioReachesTheAirPlayConfig() {
        set("microphoneAvailable", true)
        AirPlayPersistence.saveRunMode(activity, com.shilapi.xcertplay.orchestration.CarPlayRunMode.PHONE_BROWSER)
        assertEquals(CarBluetoothAudio.OFF, AirPlayPersistence.loadCarBluetoothAudio(activity))
        for ((mode, expected) in listOf(
            CarBluetoothAudio.OFF to listOf(false, false, true, true),
            CarBluetoothAudio.ON to listOf(true, false, false, false),
            CarBluetoothAudio.ALTERNATIVE to listOf(true, true, false, false),
        )) {
            AirPlayPersistence.saveCarBluetoothAudio(activity, mode)
            val airPlay = config(100)
            assertEquals(mode.name, expected,
                listOf(airPlay.audioViaCarBluetooth, airPlay.disableAudioOutput, airPlay.microphone, airPlay.receivesAudio))
        }
    }

    // A choice saved in phone + browser mode must not silence a head unit: there it behaves as off.
    @Test fun headUnitModeIgnoresASavedCarBluetoothSound() {
        set("microphoneAvailable", true)
        useHeadUnitMode(activity)
        for (mode in CarBluetoothAudio.entries) {
            AirPlayPersistence.saveCarBluetoothAudio(activity, mode)
            val airPlay = config(100)
            assertEquals(mode.name, listOf(false, false, true, true),
                listOf(airPlay.audioViaCarBluetooth, airPlay.disableAudioOutput, airPlay.microphone, airPlay.receivesAudio))
        }
    }

    // Like the other head-unit gates, a running session keeps the run mode it connected with.
    @Test fun carBluetoothSoundFollowsTheRunModeOfTheRunningSession() {
        set("microphoneAvailable", true)
        AirPlayPersistence.saveCarBluetoothAudio(activity, CarBluetoothAudio.ON)
        try {
            AirPlayPersistence.saveRunMode(activity, com.shilapi.xcertplay.orchestration.CarPlayRunMode.PHONE_BROWSER)
            set("controller", org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java))
            assertTrue("a head-unit session keeps its sound", config(100).receivesAudio)

            useHeadUnitMode(activity)
            val phone = org.mockito.Mockito.mock(com.shilapi.xcertplay.orchestration.CarPlayController::class.java)
            org.mockito.Mockito.`when`(phone.phoneBrowserMode()).thenReturn(true)
            set("controller", phone)
            assertFalse("a phone + browser session keeps car Bluetooth sound", config(100).receivesAudio)
        } finally {
            set("controller", null)
        }
        assertTrue("without a session the saved head-unit mode applies", config(100).receivesAudio)
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun phoneBrowserModeSendsANeutralCarButtonInsteadOfTheBydLogo() {
        val byd = activity.resources.openRawResource(com.shilapi.xcertplay.host.R.raw.ic_car_home).use { it.readBytes() }
        assertEquals(AirPlayPersistence.DEFAULT_OEM_LABEL, AirPlayPersistence.loadOemLabel(activity))

        AirPlayPersistence.saveRunMode(activity, com.shilapi.xcertplay.orchestration.CarPlayRunMode.PHONE_BROWSER)
        val phone = config(100)
        assertEquals("TiPlay", phone.oemLabel)
        val icon = phone.icons.single()
        assertEquals(CarButtonDefaults.ICON_SIZE_PX, icon.widthPixels)
        assertEquals(CarButtonDefaults.ICON_SIZE_PX, icon.heightPixels)
        assertFalse("phone + browser mode must not send the BYD logo", icon.data.contentEquals(byd))
        val decoded = android.graphics.BitmapFactory.decodeByteArray(icon.data, 0, icon.data.size)
        assertEquals(CarButtonDefaults.ICON_SIZE_PX, decoded.width)

        useHeadUnitMode(activity)
        val headUnit = config(100)
        assertEquals(AirPlayPersistence.DEFAULT_OEM_LABEL, headUnit.oemLabel)
        assertTrue("a head unit keeps the packaged icon", headUnit.icons.single().data.contentEquals(byd))
    }

    @Test fun aCustomCarButtonNameIsKeptInBothRunModes() {
        AirPlayPersistence.saveOemLabel(activity, "My car")
        for (mode in com.shilapi.xcertplay.orchestration.CarPlayRunMode.entries) {
            AirPlayPersistence.saveRunMode(activity, mode)
            assertEquals(mode.name, "My car", config(100).oemLabel)
        }
    }

    // Defense in depth behind the session's SETUP gate: without received audio the host also turns off
    // audio focus, the call echo canceller, the microphone and audio capture, whatever their own settings say.
    @Test fun noReceivedAudioTurnsOffFocusEchoCancellerMicrophoneAndCapture() {
        set("microphoneAvailable", true)
        AirPlayPersistence.saveAudioFocusEnabled(activity, true)
        AirPlayPersistence.saveCallEchoCancellation(activity, true)
        java.io.File(activity.filesDir, "audio-capture.enabled").writeText("")
        for (receivesAudio in listOf(true, false)) {
            val sink = activity.javaClass.getDeclaredMethod("createMediaSink", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, 800, 480, 0, receivesAudio) as AndroidMediaSink
            try {
                assertEquals(receivesAudio, field(sink, "audioFocusEnabled"))
                assertEquals(receivesAudio, field(sink, "callEchoCancellation"))
                val engine = activity.javaClass.getDeclaredMethod("createMediaEngine", AndroidMediaSink::class.java,
                    Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, sink, receivesAudio)!!
                assertEquals(receivesAudio, field(engine, "microphoneEnabled"))
                assertEquals(receivesAudio, field(engine, "audioCaptureDirectory") != null)
            } finally {
                sink.close()
            }
        }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)

    private fun config(percent: Int, uiPercent: Int = 100, width: Int = 1920, height: Int = 990): AirPlayConfig {
        set("displayScalePercent", percent)
        set("uiScalePercent", uiPercent)
        AirPlayPersistence.saveDisplayScalePercent(activity, percent)
        AirPlayPersistence.saveUiScalePercent(activity, uiPercent)
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height)
        return activity.javaClass.getDeclaredMethod("createAirPlayConfig", sizeClass)
            .apply { isAccessible = true }.invoke(activity, size) as AirPlayConfig
    }

    private fun decoder(maxWidth: Int, maxHeight: Int, maxFps: Double = 120.0, alignment: Int = 2, hardware: Boolean = true) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, maxWidth, maxHeight).apply {
            setString("alignment", "${alignment}x$alignment")
            setString("frame-rate-range", "1-${maxFps.toInt()}")
        }
        val level = MediaCodecInfo.CodecProfileLevel().apply {
            profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            this.level = MediaCodecInfo.CodecProfileLevel.AVCLevel52
        }
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
            .setProfileLevels(arrayOf(level)).build()
        val codec = MediaCodecInfoBuilder.newBuilder().setName("c2.test.hardware")
            .setIsHardwareAccelerated(hardware).setIsSoftwareOnly(!hardware).setCapabilities(capabilities).build()
        ShadowMediaCodecList.addCodec(codec)
    }

    private fun get(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any?) {
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)
    }
}
