# Browser link end-to-end check

`run.mjs` opens `site/play/` in headless Chromium against `fake-phone.mjs`, a Node stand-in for the phone's HTTP
server (protocol v1: `/hello`, `/video`, `/control`, `/bye`, CORS preflights). CI does not run it.

It serves the page from `http://127.0.0.1` under a nested path, which is a secure context, so WebCodecs and the
Service Worker work without HTTPS. Headless Chromium decodes VP8 and VP9 but not H.264, so the fake phone streams VP8
(and VP9 for the MSE check).
The page does not assume H.264 or HEVC; it configures `VideoDecoder` with whatever codec string the config record names.

## Requirements

- Node 22.
- `playwright-core`, installed anywhere. Do not add it to this repository.
- A Chromium build. Set `CHROMIUM` to its executable; the default is `/opt/pw-browsers/chromium-1194/chrome-linux/chrome`.
- Optional: `ffmpeg` and `ffprobe` with libvpx, libvpx-vp9 and libx264 (set `FFMPEG` and `FFPROBE` if they are not
  on `PATH`). Without libvpx, or with `E2E_VP8=browser`, the script encodes the VP8 clip with the browser's own
  `VideoEncoder`. Without libvpx-vp9 or libx264 it skips the MSE and muxing checks.

## Run

```sh
npm install --prefix /tmp/pw playwright-core
NODE_PATH=/tmp/pw/node_modules node tests/web-e2e/run.mjs
```

Each check prints one `ok -` line, and the script ends with `all checks passed` or `FAILED: <reason>` (exit code 1).

## What it checks

1. The page decodes frames: the fake phone receives `dec` for epoch 1, and `tiplayStats` shows `decodedFps > 0`.
2. The link fragment is stored and removed from the address, and the stats never contain the pairing code.
3. A `vp` event matches the video area (CSS × DPR, rounded to even), and the canvas backing store has the size of
   the video area in device pixels (a page that connects before its decoder is ready must still size it).
4. A tap at 25 % / 75 % of the letterboxed picture arrives as touch `[0, 0.25, 0.75, 1]` and then `[0, 0.25, 0.75, 0]`.
5. A `st` stats event arrives within 8 s.
6. After the fake phone drops `/video` without an end record, the page reconnects, decodes again and repeats its
   viewport. After a garbage key frame the decoder fails; the page asks for a key frame and decodes again.
7. "Apply and reconnect" appears when `status.fit` is true, and clicking it sends `{"k":"fit"}`.
8. A `zh-CN` browser gets the Chinese page.
9. Each fallback rung decodes and maps a tap, with one feature taken away per run: `worker-chunks` (streams not
   transferable), `worker-frames` (no `OffscreenCanvas`) and `main` (no `VideoDecoder` in workers). Each run takes
   the display over, and the first page says so and stops instead of taking it back.
10. With the page server stopped, a reload is served by the Service Worker and decodes again.
11. `mse`: the fake phone serves the page itself on the first non-loopback IPv4 address. That origin is not a secure
    context, so the page plays VP9 in fragmented MP4 through MSE (this Chromium has no H.264 MSE). A garbage frame
    ends that `<video>` with a decode error; the page reconnects with a new media element and plays again.
12. Without MSE as well, the page disables Connect and names what is missing.
13. Before the browser starts, ffmpeg (libx264) checks that H.264 access units muxed by `fmp4.js` decode frame for frame.
