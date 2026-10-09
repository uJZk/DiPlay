package com.shilapi.xcertplay

/** Car Bluetooth sound (docs/todo.md): CarPlay sound plays through TeslaPlay, or stays on the iPhone's Bluetooth link to the car. */
internal enum class CarBluetoothAudio {
    OFF,
    /** Like Carlinkit's BtAudio=1: /info leaves out only audioFormats. */
    ON,
    /** Also leaves out audioLatencies and the audio feature bits (AirPlayConfig.disableAudioOutput). */
    ALTERNATIVE,
}
