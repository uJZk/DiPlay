# Install and connect

1. Park the car. Download the latest `TeslaPlay-<version>.apk` from [TeslaPlay releases](https://github.com/uJZk/DiPlay/releases). Until the first TeslaPlay release, [build the APK from source](BUILD.md).
2. Install on the Android head unit using its supported APK installation method. Do not install on the iPhone. TeslaPlay uses the package `com.ujzk.teslaplay`, so it installs next to DiPlay and does not update it or keep its settings. For an in-place TeslaPlay update that preserves settings and pairing records, use the same package/variant and a matching signing certificate.
3. Open TeslaPlay. Grant the permissions requested for the features you use: Bluetooth/Nearby devices, Wi-Fi/Location on older Android, and microphone for Siri/calls. Allow notifications for connection controls.
4. Close other phone-projection apps before connecting.

## Wireless

Built-in car hotspot is the default. Open **Settings → Connection setup → Built-in car hotspot**, turn on the car hotspot at 5 GHz if available, and save its exact name and password. Keep Bluetooth and Wi-Fi enabled on the iPhone, pair it with the car, then tap **Connect phone**. On compatible firmware, the Bluetooth handshake supplies hotspot credentials for automatic joining; core CarPlay does not require ADB. If the iPhone does not join automatically, record whether manual joining starts CarPlay and see the separately confirmed [experimental join repair](WIRELESS_HOTSPOT_JOIN.md). **Choose iPhone** changes the selected paired device. See [the setup guide](CONNECTION_SETUP.md).

Wi-Fi Direct is an alternative on Android 9+ when the head unit implements the needed firmware APIs. Android 9 uses generated group credentials and cannot verify the requested frequency; Android 10+ retains negotiated-frequency verification. Legacy pinning/cleanup limits are in [Android 9 Wi-Fi Direct](ANDROID9_WIFI_DIRECT.md). **Existing Wi-Fi / Same LAN** connects through an external router or portable Wi-Fi: connect the car and iPhone to the same network, disable router client isolation, and save that network in Connection setup. Keep Bluetooth enabled. TeslaPlay does not join the network or change the default route for you; see [Same LAN setup](EXISTING_WIFI.md). The Local hotspot option has been removed; existing selections migrate to built-in hotspot. USB remains available.

In **Settings → Connection setup → Wi-Fi Direct → Preferred channel**, Auto keeps automatic selection: beside a 5 GHz station it tries eligible saved/aligned channels and explicit 2.4 GHz before other 5 GHz/system-default fallbacks. It does not guarantee a band. You may save a supported 2.4/5 GHz channel for the next connection; an active session continues until reconnect. If the radio rejects it or creates a different channel, choose Auto or another channel and reconnect. Regional/radio support still applies, and channel choice does not establish stutter as fixed.

Optional automatic built-in-hotspot startup is off by default. On supported BYD firmware, explicitly enabling it verifies permissions for TeslaPlay's own package through already-authorized local ADB. It preserves the car's saved hotspot name/password; boot-open and connect-on-open are separate settings. The ADB fallback requires a reachable, already-authorized connection on each request and a firmware-advertised Wi-Fi tethering command; it reports success only after observing hotspot readiness. This does not establish support on every DiLink version. See [connection setup](CONNECTION_SETUP.md).

## USB

Connect the iPhone to a USB **data** port with a data-capable cable and choose **Connect with USB**. Approve USB access, Trust/CarPlay and the local VPN permission if requested. The local VPN carries the USB network link; it is not an internet VPN service. Charge-only ports/cables cannot work.

## Settings

Swipe down with the configured finger count in CarPlay to open TeslaPlay settings, or return to the home screen. Choose two, three or four fingers under **Settings → CarPlay controls**; three remains the default. Icon/text size, resolution and frame rate use **Apply and reconnect** during an active session. A selection alone does not apply; Cancel preserves the old setting. When disconnected, **Save** applies to the next connection. Other settings also apply on the next connection unless described otherwise.

Start with 30 fps, Efficient video (HEVC) off and Default icon/text size. Try 80% or 60% resolution for a slower head unit. Some iPhone/head-unit combinations still ignore icon/text scaling.

Vehicle-data mode and battery/wheel-speed/parked-video controls are under **Settings → Location → Advanced vehicle data**. Default DiLink 5.0 mode remains the default. Legacy mode is optional, uses bounded read-only detection and needs authorized network ADB; it exposes only confirmed fields. Review any prompt to replace previously saved fields. This does not enable ADB, write vehicle settings or establish support for every older head unit.


The optional square-canvas rotation and split-screen areas, fixed dock and experimental side panel are under **Display and performance**. Larger canvases increase decoder/GPU work. **Buffered music (experimental)** is also there, remains off by default and reconnects CarPlay; it accepts AAC-LC from supporting apps, not lossless audio. Independent experimental **CarPlay call keys** and **CarPlay calls on the dashboard** stay off by default in the BYD navigation/advanced vehicle area; the dashboard card needs authorized ADB and target firmware. Review [DiPlay 0.2.13 limits](RELEASE-NOTES-0.2.13.md) before opting in.

The app and release website offer seven languages, including Traditional Chinese (Taiwan). Android 13+ app-language selection is synchronized with Android settings; Android 9–12 retain a saved context override. Hong Kong/Macao and Hant use the Taiwan app translation, not separate regional editions.

## Connection recovery and reports

If reinstalling left an old group, close other projection apps, then use **Settings → Wireless connection help → Reset CarPlay Wi-Fi**. TeslaPlay asks before removing an unrecognized Wi-Fi Direct group. Updating in place is preferable to uninstalling.

Please reproduce unresolved problems on the latest TeslaPlay build and export a fresh report, even if you already sent older logs. Capture from the first connection attempt through the failure; for boot/auto-start problems, reboot and then open TeslaPlay manually to export. Use **Settings → Diagnostics → Save diagnostic report**. Android 10+ saves to **Downloads/TeslaPlay**; Android 9 uses a document picker. If the picker or Downloads storage is unavailable, the fallback TXT file is saved under **Android/data/com.ujzk.teslaplay/files/diagnostic-reports/**; the confirmation shows its exact path. If external storage is also unavailable, TeslaPlay saves the report privately. Both fallbacks offer **View report** and **Share**; select/copy text in the report view when no sharing app is available. Review the `.txt` file, then attach it to your existing [GitHub issue](https://github.com/uJZk/DiPlay/issues) or [create a new issue](https://github.com/uJZk/DiPlay/issues/new/choose), with car/head-unit model, exact DiLink/Android/firmware versions, iPhone/iOS, connection backend (USB, built-in hotspot, Wi-Fi Direct or Same LAN), relevant settings, expected/actual behavior, reproduction steps and approximate failure time. Never include your hotspot password. Nothing is uploaded automatically.

The additional wireless/media/theme/own-app-exit records help identify the failing stage; they do not establish Qin Plus startup, Wi-Fi Direct stutter, Siri, iOS 15 or day/night firmware reports as resolved. See [DiPlay 0.2.13 release notes](RELEASE-NOTES-0.2.13.md).

APK installation restrictions are controlled by your car's firmware. ADB is optional if your car supports it, not an app runtime requirement:

```sh
adb install -r TeslaPlay-<version>.apk
```

Only use a trusted computer. A different signing certificate cannot update this build; do not uninstall until you have saved any reports you need.

## BYD navigation

See [BYD navigation displays](BYD_NAVIGATION.md) for the firmware scope, map metadata requirements, settings and cleanup behavior. Native DiLink 5 display routing needs no external ADB starter. Optional DiLink 3/4 integrations have separate firmware and authorized-ADB requirements described in the guide.
