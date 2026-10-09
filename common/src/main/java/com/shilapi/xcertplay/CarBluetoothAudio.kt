package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.CarPlayController

/** Car Bluetooth sound (docs/todo.md): CarPlay sound plays through TeslaPlay, or stays on the iPhone's Bluetooth link to the car. */
internal enum class CarBluetoothAudio {
    OFF,
    /** Like Carlinkit's BtAudio=1: /info leaves out only audioFormats. */
    ON,
    /** Also leaves out audioLatencies and the audio feature bits (AirPlayConfig.disableAudioOutput). */
    ALTERNATIVE,
    ;

    companion object {
        /**
         * The car Bluetooth sound a connection uses: the saved choice in phone + browser mode only, where the car's
         * browser shows the picture and the car plays the sound over the iPhone's Bluetooth. A head unit plays the
         * sound itself, so a choice saved in phone mode can never silence it. A running [session] keeps the mode it
         * connected with ([AirPlayPersistence.isPhoneBrowserMode]); without one, the saved mode applies.
         */
        fun effective(context: Context, session: CarPlayController?): CarBluetoothAudio =
            if (AirPlayPersistence.isPhoneBrowserMode(context, session)) AirPlayPersistence.loadCarBluetoothAudio(context) else OFF
    }
}
