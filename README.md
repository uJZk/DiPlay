# TeslaPlay

**CarPlay in the Tesla browser from an Android phone, and CarPlay on compatible BYD Android head units.** Independent app: `com.ujzk.teslaplay`.

The Tesla browser mode is in development and is not in a release yet. In this mode an Android phone runs TeslaPlay and hosts a Wi-Fi hotspot. The iPhone connects to the phone with wireless CarPlay, the Tesla joins the same hotspot, and the Tesla's built-in browser shows CarPlay and sends touches back to the phone. The phone passes the iPhone's H.264 or HEVC video to the browser without re-encoding. Music, navigation prompts, Siri and calls stay on the Bluetooth connection between the iPhone and the Tesla. See the [plan and test results](docs/todo.md) (Chinese). TeslaPlay also keeps DiPlay's CarPlay receiver for compatible BYD Android head units, described below.

TeslaPlay is based on [DiPlay](https://github.com/shihabal3amri/DiPlay) by shihabal3amri, GPL-3.0. It is not affiliated with or endorsed by Tesla, Inc., Apple Inc. or BYD. Tesla and CarPlay are trademarks of their respective owners.

> **BYD support scope:** The head-unit mode focuses on BYD cars. It may work on other brands, but other brands are unsupported and there are no plans to add support or fix brand-specific incompatibilities.

[Releases](https://github.com/uJZk/DiPlay/releases) · [Report a problem](https://github.com/uJZk/DiPlay/issues/new/choose) · [Upstream DiPlay](https://github.com/shihabal3amri/DiPlay)

## Status

There is no TeslaPlay release yet. To try it now, [build it from source](docs/BUILD.md). TeslaPlay starts from DiPlay 0.2.13. It uses its own package, `com.ujzk.teslaplay`, so it installs next to DiPlay (`com.shihab.diplay`) and does not take over DiPlay's settings, pairing records or updates.

## Tesla browser mode (in development)

- **Android phone:** runs TeslaPlay and its Wi-Fi hotspot. Reaching the phone from the Tesla browser needs root: the browser blocks private addresses such as 192.168.x.x and 10.x.x.x, so an optional setting will add an address from 100.64.0.0/10 to the hotspot interface.
- **iPhone:** pair it over Bluetooth with the Tesla as the primary phone for audio and calls, and connect it to TeslaPlay with wireless CarPlay.
- **Tesla:** join the phone's hotspot and open the TeslaPlay page in the browser. The page will load once from a public HTTPS address and then work from the browser cache.
- **Tested so far:** probe pages on a Model Y (Chromium 148) decoded 1080p60 H.264 and HEVC in hardware, and H.264 kept 60 fps in Drive. An HTTPS page in the Tesla browser reached the phone over HTTP at a 100.64 address on the hotspot. CarPlay itself has not run in a Tesla yet.

## Head-unit mode (from DiPlay 0.2.13)

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. Core CarPlay does not require ADB; optional dashboard, battery, wheel-speed and parked-video features do. Your head unit must permit APK installation. The APK supports Android 9+ (API 28); wireless supports Wi-Fi Direct, the car’s existing hotspot or Existing Wi-Fi / Same LAN. Android 9 Wi-Fi Direct uses a firmware-dependent legacy path with generated group credentials and unverified requested frequency; see [Android 9 Wi-Fi Direct](docs/ANDROID9_WIFI_DIRECT.md). Android 10+ verifies its negotiated group frequency.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto and DiPlay. Run one projection app at a time.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay or TeslaPlay. A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. The head-unit mode invites community testing; it is not a guarantee of universal compatibility.

DiPlay releases were tested on the development DiLink5.1 car: live windshield guidance and street names work, Car hotspot now starts CarPlay, and Wi-Fi Direct performance is substantially improved. Occasional audio cutouts remain and are deferred to a later update. The floating-map test build was installed on the development DiLink 5.1 car; feedback led to the pinch corrections in 0.2.9. Earlier wheel-speed and video contributions were tested on a BYD Tang with DiLink 5.0 and an iPhone 15 Pro on iOS 27; wheel-speed dead reckoning in tunnels remains unverified. Broader head-unit and iOS compatibility is not guaranteed. The HUD firmware scope and cleanup limits are documented in [BYD navigation](docs/BYD_NAVIGATION.md).

### Inherited from DiPlay 0.2.13

- Android 9 Wi-Fi Direct, IPv4-first hotspot endpoints, safer Auto channel ordering and wireless startup without unused NSD/USB services.
- Guarded rendered-video handoff fallback, safe wireless-to-USB switching and checked, cancellable permission setup.
- Narrow USBMUX trailer recovery that preserves valid payload replies.
- Optional live dock/split-screen areas and square-canvas rotation, plus the selected-decoder capability check and default-off experimental side panel.
- DiLink 4 casting/calibration and live cluster picture controls; checked DiLink 3 projection entry with compensation.
- Recent turn-card retention across wireless replacement, a finer dashboard map choice, battery-protocol fallback and eligible wheel-service recovery.
- The observed Siri microphone timestamp correction and TCP_NODELAY touch events, with device-specific performance limits.
- **Off by default:** independent experimental DiLink 3 call keys/dashboard calls and AAC-LC buffered music. Read their firmware/audio/restoration limits before opting in. Eligible hotspot join repair is a separately confirmed Check/Apply/Restore action.
- Clearer settings, saved-menu behavior, car-button customization and day/night-aware waiting screens.
- Traditional Chinese (Taiwan), bringing both the app and release website to seven languages.

See [DiPlay 0.2.13 release notes](docs/RELEASE-NOTES-0.2.13.md) and [validation](docs/VALIDATION.md) for all reviewed contributions, hardware evidence and remaining physical tests. Higher resolution and large square canvases cost more decoder/GPU work. General stutter, calls/Siri, decoder, old-iOS startup and model-specific reports remain under investigation. [DiPlay 0.2.12 notes](docs/RELEASE-NOTES-0.2.12.md) remain available as historical guidance.

## Report a problem

Report TeslaPlay problems in this repository, not in the DiPlay tracker. Reproduce the problem on the latest TeslaPlay build, then use **Settings → Diagnostics → Save diagnostic report**. Android 10+ normally saves to **Downloads/TeslaPlay**; Android 9 uses the document picker. If unavailable, use **View report** or **Share** from the confirmation, which identifies external/private fallback storage. Review the `.txt` and add it to a matching [existing issue](https://github.com/uJZk/DiPlay/issues), or [create one](https://github.com/uJZk/DiPlay/issues/new/choose). Include vehicle/head-unit model, exact firmware and Android/DiLink, phone/iOS, connection backend, relevant settings, steps and failure time. Reports are shared only when you choose; never post your hotspot password.

## Documentation

[Existing Wi-Fi / Same LAN](docs/EXISTING_WIFI.md) keeps the iPhone and head unit
on an external router. See the guide for setup, build requirements and the
BYD DiLink 4.0 / Android 10 clean-install validation result.

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Smooth wireless CarPlay](docs/SMOOTH_WIRELESS.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md) — select `mobile` for the main TeslaPlay app; `maphost` is a map sample.
- [Tesla browser plan](docs/todo.md) (Chinese)
- [Validation](docs/VALIDATION.md)
- [Release notes](CHANGELOG.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

The app and release website are available in English, Arabic, Russian, Ukrainian, Spanish, Simplified Chinese and Traditional Chinese (Taiwan). Traditional Chinese uses Taiwan wording; the app also recognizes Hong Kong/Macao and explicit Hant selections without claiming separate regional translations. Choose the app language in Settings; on Android 13+, it stays synchronized with Android’s per-app language setting.

## Source and credits

TeslaPlay is a modified version of [DiPlay](https://github.com/shihabal3amri/DiPlay) by shihabal3amri, GPL-3.0, renamed in October 2026. DiPlay is based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. DiPlay's changelog entries and release notes keep their original name. Tesla is a trademark of Tesla, Inc. CarPlay and its icon belong to Apple Inc. No Tesla, Apple or BYD affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly select an external asset directory. Publishing the APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.
