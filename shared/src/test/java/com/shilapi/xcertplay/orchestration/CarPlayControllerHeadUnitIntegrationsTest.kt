package com.shilapi.xcertplay.orchestration

import android.content.Intent
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.hud.BydClusterMapPause
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.network.WirelessHotspotBackend
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayControllerHeadUnitIntegrationsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val airPlayConfig = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480))
    private val identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test")
    private val identification = Iap2IdentificationConfig(
        name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
        firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
    )
    private val controllers = mutableListOf<CarPlayController>()
    private val phones = mutableListOf<AirPlaySession>()

    @Before fun startWithoutOutputs() {
        BydNavigationOutputs.endNow()
        BydClusterMapPause.streamControl = null
    }

    @After fun tearDown() {
        controllers.forEach { it.close(); it.awaitClosed(2_000) }
        phones.forEach { it.close() }
        BydNavigationOutputs.endNow()
    }

    @Test fun phoneBrowserModeStartsNoBydOutputAndKeepsThePhoneHomeScreenClosed() {
        val controller = controller(headUnitIntegrations = false)
        val phone = phone()
        sessionListener(controller).onSessionActive(phone)
        sessionListener(controller).onHostUiRequested(phone)
        // Android 10 with a hotspot backend is where the head unit pauses Wi-Fi scans over ADB.
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "pauseWifiScans",
            ReflectionHelpers.ClassParameter(WirelessHotspotBackend::class.java, WirelessHotspotBackend.MANUAL_HOTSPOT))

        assertFalse(bydOutputsAccepting())
        assertNull(BydClusterMapPause.streamControl)
        assertNull(shadowOf(app).nextStartedActivity)
        assertNull(ReflectionHelpers.getField<Any?>(controller, "wifiScanPause"))
    }

    @Test fun headUnitModeStartsTheBydOutputsAndOpensTheCarHomeScreen() {
        val controller = controller(headUnitIntegrations = true)
        val phone = phone()
        sessionListener(controller).onSessionActive(phone)
        sessionListener(controller).onHostUiRequested(phone)

        assertTrue(bydOutputsAccepting())
        assertNotNull(BydClusterMapPause.streamControl)
        val home = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, home.action)
        assertTrue(home.hasCategory(Intent.CATEGORY_HOME))
    }

    @Test fun headUnitIntegrationsStayOnByDefault() {
        assertTrue(CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = identification).headUnitIntegrations)
    }

    private fun controller(headUnitIntegrations: Boolean): CarPlayController = CarPlayController(
        app,
        CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = identification,
            headUnitIntegrations = headUnitIntegrations),
        airPlayConfig, identity, PairingStore(),
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}, {},
    ).also { controllers += it }

    private fun phone(): AirPlaySession = AirPlaySession(Socket(), airPlayConfig, identity, PairingStore(), null,
        object : AirPlaySessionListener {}, object : AirPlayMediaHandler {}).also { phones += it }

    private fun sessionListener(controller: CarPlayController): AirPlaySessionListener =
        ReflectionHelpers.getField(controller, "sessionListener")

    private fun bydOutputsAccepting(): Boolean = listOf("standalone", "hud", "cluster").any { worker ->
        ReflectionHelpers.getField<Boolean>(ReflectionHelpers.getField<Any>(BydNavigationOutputs, worker), "accepting")
    }
}
