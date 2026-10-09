package com.shilapi.xcertplay

import com.shilapi.xcertplay.hud.BydCarPlayCall
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRunMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayCallControlsOptInTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun cleanup() {
        BydOutputSettings.setCarPlayCallControls(app, false)
        AirPlayPersistence.saveRunMode(app, CarPlayRunMode.HEAD_UNIT)
        BydCarPlayCall.end()
    }

    @Test fun newCallKeysCannotConsumeOrSendAnythingWithoutOptIn() {
        val controller = mock(CarPlayController::class.java)
        for (code in listOf(313, 314, 309)) {
            assertFalse(CarPlayCallKeys.onKey(app, code, true, controller))
            assertFalse(CarPlayCallKeys.onKey(app, code, false, controller))
        }
        verifyNoInteractions(controller)
    }

    @Test fun explicitOptInEnablesAnswerAndDisableRestoresPassThrough() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 2); string(4, "incoming-call")
        })
        BydOutputSettings.setCarPlayCallControls(app, true)
        assertTrue(CarPlayCallKeys.onKey(app, 313, true, controller))
        assertTrue(CarPlayCallKeys.onKey(app, 313, false, controller))
        verify(controller).answerCall()
        BydOutputSettings.setCarPlayCallControls(app, false)
        assertFalse(CarPlayCallKeys.onKey(app, 314, false, controller))
        verify(controller, never()).endCall()
    }

    @Test fun phoneBrowserModeLeavesTheKeysToThePhoneDespiteASavedOptIn() {
        val controller = mock(CarPlayController::class.java)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        `when`(controller.phoneBrowserMode()).thenReturn(true)
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 2); string(4, "incoming-call")
        })
        BydOutputSettings.setCarPlayCallControls(app, true)
        // The session connected in phone + browser mode; head unit saved since then waits for the next connection.
        AirPlayPersistence.saveRunMode(app, CarPlayRunMode.HEAD_UNIT)

        assertFalse(CarPlayCallKeys.onKey(app, 313, true, controller))
        assertFalse(CarPlayCallKeys.onKey(app, 313, false, controller))
        verify(controller, never()).answerCall()
    }

    @Test fun aHeadUnitSessionKeepsItsCallKeysUntilItReconnectsInPhoneBrowserMode() {
        val controller = mock(CarPlayController::class.java) // connected as a head unit
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        BydCarPlayCall.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            u8(2, 2); string(4, "incoming-call")
        })
        BydOutputSettings.setCarPlayCallControls(app, true)
        AirPlayPersistence.saveRunMode(app, CarPlayRunMode.PHONE_BROWSER)

        assertTrue(CarPlayCallKeys.onKey(app, 313, true, controller))
        assertTrue(CarPlayCallKeys.onKey(app, 313, false, controller))
        verify(controller).answerCall()
    }
}
