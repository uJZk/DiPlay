# TeslaPlay next release notes

TeslaPlay is a fork of [DiPlay](https://github.com/shihabal3amri/DiPlay). Changes through DiPlay 0.2.13 are documented in [DiPlay 0.2.13 release notes](RELEASE-NOTES-0.2.13.md). Measured checks are in [VALIDATION.md](VALIDATION.md). Add future unreleased changes here.

## Rename

- The project is now called TeslaPlay, with the package `com.ujzk.teslaplay`. It installs alongside DiPlay and does not reuse DiPlay's settings or pairing records.

## Sound through car Bluetooth

- Settings → Audio has a new experimental choice, "Sound through car Bluetooth", for cars that show CarPlay in their web browser, such as Tesla. When it is on, `/info` leaves out only `audioFormats`, as the Carlinkit `BtAudio=1` mode does; audio latencies, feature bits and Bluetooth IDs stay the same. TeslaPlay then declines any audio stream the iPhone still opens and starts no audio output, microphone, echo canceller or audio focus. "On, alternative method" also leaves out the audio latencies and audio feature bits. The choice is off by default and applies at the next CarPlay connection. It is not yet verified in a car.
- The diagnostic report shows the saved choice, the audio route at each start, the `/info` audio declaration and every audio stream the iPhone asks for.

## Connection reliability

- USBMUX accepts the bounded protocol-1 diagnostic frame with the four-byte trailer captured in [DiPlay issue #100](https://github.com/shihabal3amri/DiPlay/issues/100), including fragmented reads. Recovery still requires a validated preceding reply and a validated next frame; unknown data is rejected. This repairs that captured framing failure, without claiming the entire reported USB connection succeeds.
- Video uses SurfaceView when the attached app window lacks hardware acceleration. Hardware-accelerated windows retain TextureView. Letterboxing, view-area cropping and touch mapping share the same viewport. Picture color adjustments are unavailable on SurfaceView and the controls explain this; saved adjustments remain available for TextureView output.
- Bluetooth checks both RFCOMM streams before starting iAP2 and reports whether reading fails before any bytes arrive. Stream failures preserve their cause and close the socket once. Draining a full receive queue wakes the reader so it can continue.
- USB bulk-IN makes one 16 KiB queue attempt after an explicitly rejected larger request, remembering the smaller size only when it succeeds. Exceptions and timed-out requests are not retried this way. This compatibility path needs an affected-device retest; Android 9 normally supports the original larger sizes.

These changes retain Android 9 / API 28 as the APK minimum. Device acceptance and remaining failure families are tracked in [connection reliability validation](CONNECTION_RELIABILITY.md).
