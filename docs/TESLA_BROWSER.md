# Tesla browser

TiPlay's phone + browser mode shows CarPlay in the Tesla's built-in browser. It is in development and has not run in a
car yet; see the test checklist at the end.

- An Android phone runs TiPlay and its Wi-Fi hotspot. The iPhone connects to TiPlay with wireless CarPlay.
- The Tesla joins the hotspot and opens the TiPlay page. The phone sends the iPhone's H.264 or HEVC video to the page
  without re-encoding, over plain HTTP on port 8080, and the page sends touches back.
- Music, navigation prompts, Siri and calls stay on the Bluetooth connection between the iPhone and the Tesla.

The protocol and the reasons behind it are in [the plan](todo.md) (Chinese).

## 1. Publish the page

The car opens a public HTTPS page: the browser decodes video with WebCodecs, which needs a secure context, and Chrome
lets an HTTPS page `fetch` plain HTTP from a local IP address such as `100.109.220.253`.

**GitHub Pages (default).** The page is `site/play/`. `.github/workflows/pages.yml` publishes all of `site/` when
`site/**` changes on `main`. In a fork, open **Settings → Pages** and choose **GitHub Actions** as the source. The page is
then `https://<owner>.github.io/<repository>/play/`. TiPlay's default is `https://ujzk.github.io/DiPlay/play/`.

**Another host.** Copy the files of `site/play/` unchanged into one directory of any HTTPS static host. Serve `.js` as
`text/javascript`. Prefer a host the car can reach without a proxy: in mainland China `github.io` and `workers.dev` can be
slow or blocked, so a custom domain is safer. Then set **Page address** in TiPlay to the directory's URL.

After the first visit the page's Service Worker serves it from the browser cache, so the car does not need internet
access later. When you change a file in `site/play/`, increase `VERSION` in `sw.js` so browsers fetch the new files.

## 2. Set up TiPlay

Open **Settings → Connection → Tesla browser (experimental)**.

- **Phone and car browser** is on by default. Turn it off only when TiPlay runs on a car head unit.
- The status line says whether the link listens on port 8080, whether another app uses the port (TiPlay keeps trying),
  and on Android 17 whether TiPlay has local network access. Tap **Allow local network access** if it does not.
- **Page address** is the HTTPS page. It must start with `https://`.
- **Pairing code** shows the 6-digit code. **New code** disconnects the browser that uses the old code.
- The first link is for the Tesla and other Chromium browsers. Open it in the car's browser, or copy it to send it to
  yourself. It has the form `https://ujzk.github.io/DiPlay/play/?t=100.109.220.253#c=123456`.
- The second link is for other browsers (see section 5): `http://<phone address>:8080/play/#c=123456`.

The link to the HTTPS page names the phone in `?t=`: the extra hotspot address when it is on, else the hotspot's own
address (`:port` follows only when it is not 8080). The page keeps `?t=` in the address bar, so a bookmark opens the same
phone. The phone's own page talks to the address it was loaded from and needs no `?t=`.

## 3. Pairing

The pairing code is created once and kept until you tap **New code**. The link carries it in the fragment (`#c=…`). The
page stores it in the browser and removes the fragment, so the code reaches no server and stays out of the history and
bookmarks.
You can also type the code on the page.

One browser shows CarPlay at a time: the latest page that pairs takes over, and the previous one says so. After five wrong
codes within 30 seconds, the phone refuses every browser for 30 seconds. The video is not encrypted on the hotspot; the
hotspot's WPA password protects it.

## 4. The phone's address

The Tesla browser blocks private addresses (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8), which phone hotspots
use. It opens addresses in 100.64.0.0/10.

- **With root:** turn on **Extra hotspot address (experimental)** in the same card. TiPlay adds `100.109.220.253` (or the
  address you set) to the hotspot interface, and again after the hotspot restarts. The link names that address in
  `?t=`.
- **Without root:** no tested way exists yet. See the 169.254 test in section 9.

## 5. Other browsers

The phone also serves its own copy of the page at `http://<phone address>:8080/play/`, built from `site/play/` into the
app. It needs no internet access and no pairing code to load.

- Over `http://` the page is not a secure context, so it plays video through Media Source Extensions (fragmented MP4)
  instead of WebCodecs. Safari uses its Managed Media Source.
- Firefox usually cannot play HEVC. If the page says so, turn off **Efficient video** (HEVC) in TiPlay.
- The address in the link is the extra hotspot address once TiPlay added it, else the hotspot's own address. Any device
  on the hotspot can open it. The Tesla should use the HTTPS link: over `http://` it would fall back to Media Source
  Extensions, which is slower to start and less tested.
- A browser on the phone itself can open `http://localhost:8080/play/`, which is a secure context.

## 6. Picture size

CarPlay's canvas is the size of the page's video area in device pixels (CSS pixels × device pixel ratio, rounded down to
even numbers, at most 3840 × 2160 in either orientation), at 60 fps. Before any browser has reported its size, TiPlay
uses 1280 × 720. CarPlay's physical size follows the page's CSS size (0.2646 mm per CSS pixel), so its controls are as
large as the browser's own text. The head unit's resolution, CarPlay size, safe area, dock, split screen, turning screen
and side panel settings do not apply.

When the browser window changes size, the page shows **Apply and reconnect**. Only that button reconnects CarPlay at the
new size; TiPlay saves the size for the next connection either way. The phone's own screen shows the same picture with
black bars and never changes the canvas.

## 7. Screen off and permissions

While CarPlay is connected in this mode, TiPlay keeps the CPU awake and Wi-Fi in its low-latency mode, so the car keeps
its picture with the phone's screen off. Android 17 asks for local network access (Nearby devices) when CarPlay
starts; without it neither the iPhone nor the car can reach the phone.

## 8. Known limits

- Not tested in a car yet. HEVC decodes only in hardware on the Tesla tested so far.
- Another app on port 8080 blocks the link until it frees the port.
- The phone listens on port 8080 of every IPv4 address, on every network it joins. Without the pairing code a device
  gets only the page and `/hello`.
- At most 8 connections at a time. One browser needs two or three (video, controls, and one per file of the phone's own
  page while it loads, closed after each file); several browsers at once can be refused.
- Chrome may tighten its local network access rules, and Tesla may start asking for permission or block 100.64.0.0/10.
- Sound never goes through the browser.

## 9. Car test checklist

Note the Tesla software version, the iOS version and the phone model for each run, and save a TiPlay diagnostic report.
Its header has the lines `Run mode …` and `Browser link: state=… lastViewport=… pairingCode=yes|no
localNetworkPermission=…`; the session log has `Browser link: …`, `Web video: …` and every 10 s
`Browser: path=… fps=… decodeP95Ms=… gaps=…`.

- [ ] The link pairs: the page connects, and its address bar no longer shows `#c=`.
- [ ] H.264 plays at 60 fps (stats overlay); then HEVC (**Efficient video** on) after a reconnect.
- [ ] Touch: tap, drag a list, pinch the map with two fingers. A finger lifted outside the picture is released.
- [ ] In Drive the picture keeps playing (the probe pages kept 60 fps).
- [ ] After a 30-minute drive the video stream is still the first one (`reconnects=0` in `Browser:` lines).
- [ ] With the phone's screen off for 10 minutes the picture keeps playing.
- [ ] With the phone's screen off from the start, CarPlay still connects and the session log shows
      `Web video: browser rendered epoch=1`: the browser's first decoded frame is the wireless connection proof.
- [ ] Resize or split the browser window: **Apply and reconnect** appears; after it, the picture fills the area.
- [ ] Turn the phone's hotspot off and on: the extra address comes back and the page reconnects.
- [ ] After a reload without internet access, the page opens from the cache and plays.
- [ ] **New code** disconnects the car; the new link pairs it again.
- [ ] A second browser (another phone on the hotspot) takes over; the car's page says so.
- [ ] Safari and Firefox on another device play the phone-served page.
- [ ] **169.254 test (no root).** Android 13 and later drop packets that arrive on another interface for a VPN's own
      addresses, except link-local ones (`ConnectivityService.generateIngressDiscardRules` skips
      `isLinkLocalAddress()`). The Tesla does not block 169.254.0.0/16 and Chrome treats it as local. So the address of a
      VPN that TiPlay or a test app starts (`VpnService.Builder.addAddress("169.254.77.1", 32)`, which needs no root)
      might be reachable from the car through the hotspot. With such a VPN running and the extra hotspot address off,
      open `https://<page>/play/?t=169.254.77.1#c=<code>` in the car and note whether the page reaches the phone,
      and whether wireless CarPlay keeps working while the VPN runs. This is untested: the car may not route
      169.254.0.0/16 to the hotspot's gateway.
