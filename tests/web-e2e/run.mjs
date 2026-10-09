// SPDX-License-Identifier: GPL-3.0-only
// End-to-end check of site/play against the fake phone in headless Chromium. Not part of CI; see README.md.
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { networkInterfaces, tmpdir } from 'node:os';
import { extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseIvf, startFakePhone } from './fake-phone.mjs';
import { TIMESCALE, initSegment, mediaSegment, annexBToLengthPrefixed } from '../../site/play/fmp4.js';

// createRequire honours NODE_PATH, which a bare ESM import does not.
const { chromium } = createRequire(import.meta.url)('playwright-core');

const CODE = '271828';
const WIDTH = 800, HEIGHT = 480, FPS = 30;
const PLAY = fileURLToPath(new URL('../../site/play/', import.meta.url));
const PAGE_PATH = '/nested/deeper/play/'; // the page must not assume where it is hosted
const CHROMIUM = process.env.CHROMIUM ?? ['/opt/pw-browsers/chromium-1194/chrome-linux/chrome'].find(existsSync);
const FFMPEG = process.env.FFMPEG ?? 'ffmpeg';
const FFPROBE = process.env.FFPROBE ?? FFMPEG.replace(/ffmpeg$/, 'ffprobe');

// Init scripts that take one feature away, to drive the page down its fallback ladder.
const NO_STREAM_TRANSFER = `for (const type of [MessagePort, Worker]) {
  const post = type.prototype.postMessage;
  type.prototype.postMessage = function (message, transfer) {
    if ([].concat(transfer?.transfer ?? transfer ?? []).some(item => item instanceof ReadableStream)) throw new DOMException('refused', 'DataCloneError');
    return post.apply(this, arguments);
  };
}`;
const NO_OFFSCREEN_CANVAS = 'delete HTMLCanvasElement.prototype.transferControlToOffscreen;';
// Init scripts do not reach workers, so this one rewrites the worker module itself (imports still run first).
const noWorkerVideoDecoder = async context => {
  await context.route('**/decoder-worker.js', async route => {
    const response = await route.fetch();
    await route.fulfill({ response, body: `self.VideoDecoder = undefined;\n${await response.text()}` });
  });
};

function log(message) {
  console.log(`[e2e] ${message}`);
}

async function waitFor(label, predicate, timeout = 15000) {
  const until = Date.now() + timeout;
  for (;;) {
    const value = await predicate();
    if (value) return value;
    if (Date.now() > until) throw new Error(`timed out waiting for ${label}`);
    await new Promise(resolve => setTimeout(resolve, 50));
  }
}

function check(condition, message) {
  if (!condition) throw new Error(message);
  log(`ok - ${message}`);
}

function ffmpeg(args) {
  return spawnSync(FFMPEG, ['-hide_banner', '-loglevel', 'error', ...args]);
}

/** A two-second test clip as IVF frames from ffmpeg's libvpx, or null when ffmpeg or libvpx is missing. */
function encodeIvf(encoder) {
  if (process.env.E2E_VP8 === 'browser' && encoder === 'libvpx') return null;
  const dir = mkdtempSync(join(tmpdir(), 'tiplay-e2e-'));
  try {
    const clip = join(dir, 'clip.ivf');
    const result = ffmpeg(['-f', 'lavfi', '-i', `testsrc2=size=${WIDTH}x${HEIGHT}:rate=${FPS}`, '-t', '2', '-c:v', encoder,
      '-deadline', 'realtime', '-cpu-used', '8', '-lag-in-frames', '0', '-auto-alt-ref', '0', '-g', String(FPS / 2), '-b:v', '1M', '-f', 'ivf', clip]);
    if (result.status !== 0) {
      log(`ffmpeg with ${encoder} unavailable (${result.error?.message ?? result.stderr.toString().trim()})`);
      return null;
    }
    return parseIvf(readFileSync(clip));
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** Fallback: encode VP8 frames with VideoEncoder in the browser itself. */
async function encodeInBrowser(context, origin) {
  const page = await context.newPage();
  await page.goto(`${origin}/blank`);
  const frames = await page.evaluate(async ({ width, height, fps }) => {
    const canvas = new OffscreenCanvas(width, height), context = canvas.getContext('2d');
    const chunks = [];
    const encoder = new VideoEncoder({
      output: chunk => {
        const data = new Uint8Array(chunk.byteLength);
        chunk.copyTo(data);
        let binary = '';
        for (const byte of data) binary += String.fromCharCode(byte);
        chunks.push({ key: chunk.type === 'key', data: btoa(binary) });
      },
      error: error => { throw error; },
    });
    encoder.configure({ codec: 'vp8', width, height, bitrate: 1_000_000, framerate: fps, latencyMode: 'realtime' });
    for (let i = 0; i < fps * 2; i++) {
      context.fillStyle = `hsl(${(i * 6) % 360} 60% 35%)`;
      context.fillRect(0, 0, width, height);
      context.fillStyle = '#fff';
      context.font = '96px sans-serif';
      context.fillText(String(i), 40, 140);
      const frame = new VideoFrame(canvas, { timestamp: Math.round(i * 1e6 / fps) });
      encoder.encode(frame, { keyFrame: i % (fps / 2) === 0 });
      frame.close();
    }
    await encoder.flush();
    encoder.close();
    return chunks;
  }, { width: WIDTH, height: HEIGHT, fps: FPS });
  await page.close();
  return frames.map(frame => ({ key: frame.key, data: new Uint8Array(Buffer.from(frame.data, 'base64')) }));
}

/**
 * The MSE fallback's H.264 output cannot play in this Chromium (no proprietary codecs), so ffmpeg checks it instead:
 * libx264 Annex-B access units (split at AUDs) are muxed like the page does and must decode frame for frame.
 */
function checkH264Muxing() {
  const dir = mkdtempSync(join(tmpdir(), 'tiplay-fmp4-'));
  try {
    const stream = join(dir, 'clip.h264'), muxed = join(dir, 'clip.mp4');
    const encoded = ffmpeg(['-f', 'lavfi', '-i', `testsrc2=size=${WIDTH}x${HEIGHT}:rate=${FPS}`, '-t', '1', '-c:v', 'libx264',
      '-profile:v', 'high', '-bf', '0', '-x264-params', `aud=1:keyint=${FPS / 2}:repeat-headers=1`, '-f', 'h264', stream]);
    if (encoded.status !== 0) {
      log(`skipped the H.264 muxing check: ffmpeg with libx264 unavailable (${encoded.error?.message ?? encoded.stderr.toString().trim()})`);
      return;
    }
    const bytes = new Uint8Array(readFileSync(stream));
    const units = [];
    for (let i = 0; i + 4 < bytes.length; i++) {
      if (bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1) units.push({ start: i, type: bytes[i + 3] & 0x1f });
    }
    const accessUnits = [];
    units.forEach((unit, index) => {
      if (unit.type === 9) accessUnits.push({ start: unit.start, key: false });
      if (unit.type === 5) accessUnits.at(-1).key = true;
      if (index + 1 === units.length || units[index + 1].type === 9) accessUnits.at(-1).end = index + 1 < units.length ? units[index + 1].start : bytes.length;
    });
    const nal = type => {
      const at = units.findIndex(unit => unit.type === type);
      return bytes.subarray(units[at].start + 3, units[at + 1].start - (bytes[units[at + 1].start - 1] === 0 ? 1 : 0));
    };
    const sps = nal(7), pps = nal(8);
    const avcC = Uint8Array.of(1, sps[1], sps[2], sps[3], 0xff, 0xe1, sps.length >> 8, sps.length & 255, ...sps, 1, pps.length >> 8, pps.length & 255, ...pps);
    const codec = `avc1.${[...sps.subarray(1, 4)].map(byte => byte.toString(16).padStart(2, '0')).join('')}`;
    const duration = TIMESCALE / FPS;
    const parts = [initSegment({ codec, width: WIDTH, height: HEIGHT, record: avcC })];
    accessUnits.forEach((unit, index) => {
      const data = annexBToLengthPrefixed(bytes.subarray(unit.start, unit.end));
      parts.push(mediaSegment({ sequence: index + 1, decodeTime: index * duration, duration, data, key: unit.key }));
    });
    writeFileSync(muxed, Buffer.concat(parts));
    const decoded = ffmpeg(['-i', muxed, '-f', 'null', '-']);
    const probe = spawnSync(FFPROBE, ['-v', 'error', '-count_frames', '-select_streams', 'v', '-show_entries', 'stream=nb_read_frames,codec_name,width,height', '-of', 'json', muxed]);
    const info = JSON.parse(probe.stdout.toString() || '{}').streams?.[0];
    check(decoded.status === 0 && !decoded.stderr.length && Number(info?.nb_read_frames) === accessUnits.length && info.width === WIDTH,
      `ffmpeg decodes all ${accessUnits.length} H.264 frames of the MSE muxer's fMP4 (${codec})`);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

function startPageServer() {
  const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
  const server = createServer((req, res) => {
    const path = new URL(req.url, 'http://page').pathname;
    if (path === '/blank') return res.writeHead(200, { 'Content-Type': 'text/html' }).end('<!doctype html><title>blank</title>');
    const name = path === PAGE_PATH ? 'index.html' : path.startsWith(PAGE_PATH) ? path.slice(PAGE_PATH.length) : null;
    if (!name || name.includes('/') || !existsSync(join(PLAY, name))) return res.writeHead(404).end();
    res.writeHead(200, { 'Content-Type': types[extname(name)] ?? 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(readFileSync(join(PLAY, name)));
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server)));
}

const eventsOf = (phone, k, from = 0) => phone.events.slice(from).filter(entry => entry.event.k === k);
const stats = page => page.evaluate(() => window.tiplayStats);

/** Taps at (x, y) of the letterboxed picture and returns the touch events the phone received for it. */
async function tap(page, phone, x, y) {
  const rect = await page.evaluate(() => JSON.parse(JSON.stringify(document.getElementById('touch').getBoundingClientRect())));
  const scale = Math.min(rect.width / WIDTH, rect.height / HEIGHT);
  const left = rect.left + (rect.width - WIDTH * scale) / 2, top = rect.top + (rect.height - HEIGHT * scale) / 2;
  const mark = phone.events.length;
  await page.touchscreen.tap(left + x * WIDTH * scale, top + y * HEIGHT * scale);
  return waitFor('a touch down and up', () => {
    const touches = eventsOf(phone, 't', mark).map(entry => entry.event.p);
    return touches.length >= 2 && touches;
  });
}

function checkTap(touches, x, y, label) {
  const near = contact => Math.abs(contact[1] - x) < 0.01 && Math.abs(contact[2] - y) < 0.01;
  const [down, up] = [touches[0], touches.at(-1)];
  check(down.length === 1 && down[0][0] === 0 && down[0][3] === 1 && near(down[0]) && up.length === 1 && up[0][3] === 0 && near(up[0]),
    `${label}: a tap at ${x}/${y} arrives as touch down ${JSON.stringify(down[0])} then up ${JSON.stringify(up[0])}`);
}

/**
 * Sends garbage key frames until `broken(page)` holds. One is usually enough, but on a loaded machine a backlog reset
 * can discard it before it reaches the decoder.
 */
async function corruptUntil(phone, page, broken) {
  for (let attempt = 0; attempt < 5; attempt++) {
    phone.corruptNextFrame();
    const until = Date.now() + 1500;
    while (Date.now() < until) {
      if (await broken(page)) return;
      await new Promise(resolve => setTimeout(resolve, 100));
    }
  }
  throw new Error('the page took no harm from five garbage frames');
}

/** The canvas backing store must follow the video area; at the default 300 × 150 the picture is blurred and stretched. */
async function checkCanvas(page, label) {
  const expected = await page.evaluate(() => {
    const rect = document.getElementById('stage').getBoundingClientRect();
    return `${Math.round(rect.width * devicePixelRatio)}x${Math.round(rect.height * devicePixelRatio)}`;
  });
  let canvas = null;
  await waitFor('the canvas size', async () => (canvas = (await stats(page)).page.video.canvas) === expected, 3000).catch(() => {});
  check(canvas === expected, `${label}: the canvas backing store is ${canvas} for a ${expected} video area`);
}

/** Opens the page in a fresh context and checks that it decodes on `expected` and maps a tap. */
async function checkPath(browser, url, phone, expected, prepare) {
  const context = await browser.newContext({ viewport: { width: 900, height: 640 }, hasTouch: true, locale: 'en-US', serviceWorkers: 'block' });
  if (typeof prepare === 'string') await context.addInitScript(prepare);
  else if (prepare) await prepare(context);
  const page = await context.newPage();
  const mark = phone.events.length;
  await page.goto(url);
  await waitFor(`a dec event on ${expected}`, () => eventsOf(phone, 'dec', mark).length);
  await waitFor(`decoded frames on ${expected}`, async () => (await stats(page)).page.video.decodedFps > 0);
  const { link, video } = (await stats(page)).page;
  check(link.path === expected, `path ${link.path} (${link.fallback}) decodes at ${video.decodedFps} fps with renderer ${video.renderer}`);
  if (video.renderer !== 'video') await checkCanvas(page, expected);
  checkTap(await tap(page, phone, 0.6, 0.3), 0.6, 0.3, expected);
  await context.close();
  return link;
}

function lanAddress() {
  return Object.values(networkInterfaces()).flat().find(entry => entry.family === 'IPv4' && !entry.internal)?.address ?? null;
}

async function main() {
  checkH264Muxing();
  const pageServer = await startPageServer();
  const origin = `http://127.0.0.1:${pageServer.address().port}`;
  const browser = await chromium.launch({ executablePath: CHROMIUM });
  const context = await browser.newContext({ viewport: { width: 1000, height: 700 }, deviceScaleFactor: 1.5, hasTouch: true, locale: 'en-US' });
  let frames = encodeIvf('libvpx');
  if (frames) log(`encoded ${frames.length} VP8 frames with ffmpeg/libvpx`);
  else {
    frames = await encodeInBrowser(context, origin);
    log(`encoded ${frames.length} VP8 frames with the browser's VideoEncoder`);
  }
  if (!frames[0]?.key) throw new Error('the clip must start with a key frame');
  const phone = await startFakePhone({ code: CODE, frames, width: WIDTH, height: HEIGHT, fps: FPS });
  const failures = [];
  const page = await context.newPage();
  page.on('console', message => { if (message.type() === 'error') failures.push(message.text()); });
  page.on('pageerror', error => failures.push(error.message));
  const phoneServers = [phone];
  try {
    const pageUrl = `${origin}${PAGE_PATH}?t=127.0.0.1:${phone.port}#c=${CODE}`;
    await page.goto(pageUrl);

    await waitFor('a dec event', () => eventsOf(phone, 'dec').length);
    check(eventsOf(phone, 'dec')[0].event.ep === 1, 'the fake phone heard "dec" for epoch 1');
    await waitFor('decoded frames', async () => (await stats(page)).page.video.decodedFps > 0);
    const first = await stats(page);
    check(first.page.link.path === 'worker' && first.page.link.fallback === null, 'Chromium takes the Tesla path: worker, transferred stream, OffscreenCanvas');
    check(first.page.video.decodedFps > 0, `decodedFps ${first.page.video.decodedFps} with renderer ${first.page.video.renderer} (${first.page.video.acceleration})`);
    check(!JSON.stringify(first).includes(CODE), 'tiplayStats never contains the pairing code');
    check(await page.evaluate(() => location.hash === ''), 'the link fragment was removed from the address');
    check(await page.evaluate(port => location.search === `?t=127.0.0.1:${port}`, phone.port), 'the address bar keeps ?t= so a bookmark reopens this phone');
    check(await page.evaluate(code => JSON.parse(localStorage.getItem('tiplay.link')).code === code, CODE), 'the link was stored');
    check(/VP8 · \d+ fps/.test(await page.textContent('#status')), `status line reads "${await page.textContent('#status')}"`);

    const rect = await page.evaluate(() => JSON.parse(JSON.stringify(document.getElementById('touch').getBoundingClientRect())));
    const viewport = await waitFor('a viewport event', () => eventsOf(phone, 'vp').at(-1)?.event);
    check(viewport.cw === Math.round(rect.width) && viewport.ch === Math.round(rect.height) && viewport.dpr === 1.5 &&
      viewport.w === Math.round(rect.width * 1.5 / 2) * 2 && viewport.h === Math.round(rect.height * 1.5 / 2) * 2,
    `viewport event ${JSON.stringify(viewport)} matches the video area`);
    await checkCanvas(page, 'worker');

    checkTap(await tap(page, phone, 0.25, 0.75), 0.25, 0.75, 'worker');

    const st = await waitFor('a stats event', () => eventsOf(phone, 'st').at(-1)?.event, 8000);
    check(JSON.stringify(st.v).length <= 2048 && st.v['video.decodedFps'] > 0 && st.v['link.path'] === 'worker',
      `stats event with ${Object.keys(st.v).length} fields, including the path`);

    const decodedBefore = eventsOf(phone, 'dec').length, streamsBefore = phone.videoCount, viewportsBefore = eventsOf(phone, 'vp').length;
    phone.dropVideo();
    await waitFor('a new /video request', () => phone.videoCount > streamsBefore);
    await waitFor('decoding after the reconnect', () => eventsOf(phone, 'dec').length > decodedBefore);
    check(true, `the page reopened /video after the drop (${phone.videoCount} streams) and decoded again`);
    await waitFor('the viewport after the reconnect', () => eventsOf(phone, 'vp').length > viewportsBefore, 3000).catch(() => {});
    check(eventsOf(phone, 'vp').length > viewportsBefore, 'the page repeats its viewport on the new stream, for a phone that restarted');

    const keyRequests = eventsOf(phone, 'kf').length;
    await corruptUntil(phone, page, async () => (await stats(page)).page.video.decodeErrors > 0);
    const decodedAfterError = (await stats(page)).page.video.decoded;
    await waitFor('decoding after the decoder error', async () => (await stats(page)).page.video.decoded > decodedAfterError + 10);
    check(eventsOf(phone, 'kf').length > keyRequests, 'after a decoder error the page asked for a key frame and decodes again');

    phone.setFit(true);
    await page.waitForSelector('#fit:not([hidden])', { timeout: 5000 });
    check(await page.textContent('#fit') === 'Apply and reconnect', '"Apply and reconnect" appears when status.fit is true');
    await page.click('#fit');
    await waitFor('a fit event', () => eventsOf(phone, 'fit').length);
    check(true, 'the button sends {"k":"fit"}');
    phone.setFit(false);

    const zh = await browser.newContext({ locale: 'zh-CN' });
    const zhPage = await zh.newPage();
    await zhPage.goto(`${origin}${PAGE_PATH}`);
    check(await zhPage.textContent('#connect') === '连接' && await zhPage.getAttribute('html', 'lang') === 'zh-CN', 'a zh-CN browser gets the Chinese page');
    await zh.close();

    // Each later rung takes over the display from the page before, as a new session.
    await checkPath(browser, pageUrl, phone, 'worker-chunks', NO_STREAM_TRANSFER);
    await checkPath(browser, pageUrl, phone, 'worker-frames', NO_OFFSCREEN_CANVAS);
    await checkPath(browser, pageUrl, phone, 'main', noWorkerVideoDecoder);
    check(/Another browser took over/.test(await page.textContent('#status')), 'the first page, replaced by the others, says so and stops');

    // The cached page opens without the page server; the phone requests still reach the fake phone.
    await page.evaluate(() => navigator.serviceWorker.ready);
    await new Promise(resolve => pageServer.close(resolve));
    pageServer.closeAllConnections();
    const decodedOnline = eventsOf(phone, 'dec').length;
    await page.reload();
    await waitFor('decoding from the cached page', () => eventsOf(phone, 'dec').length > decodedOnline);
    check(await page.evaluate(() => Boolean(navigator.serviceWorker.controller)), 'the page reloaded from the Service Worker cache and decoded again');

    // The phone-served copy over plain HTTP is not a secure context: MSE plays VP9 in fragmented MP4 there.
    const address = lanAddress(), vp9 = address && encodeIvf('libvpx-vp9');
    if (!address || !vp9) log(`skipped the MSE check: ${address ? 'no libvpx-vp9' : 'no non-loopback IPv4 address'}`);
    else {
      const served = await startFakePhone({ code: CODE, frames: vp9, width: WIDTH, height: HEIGHT, fps: FPS, codec: 'vp09.00.21.08', host: address, pageDir: PLAY });
      phoneServers.push(served);
      const link = await checkPath(browser, `http://${address}:${served.port}/play/#c=${CODE}`, served, 'mse');
      check(link.fallback === 'not a secure context', 'the MSE rung names its reason');
      const recovering = await browser.newContext({ locale: 'en-US' });
      const mse = await recovering.newPage();
      await mse.goto(`http://${address}:${served.port}/play/#c=${CODE}`);
      await waitFor('MSE playback', async () => (await stats(mse)).page.video.decodedFps > 0);
      check(await mse.evaluate(() => location.search === '' && location.hash === ''), 'the page the phone serves needs no ?t= and drops the code fragment');
      const decodedBefore = eventsOf(served, 'dec').length;
      await corruptUntil(served, mse, () => mse.evaluate(() => window.tiplayStats.page.video.mediaErrors > 0 || document.getElementById('video').error !== null));
      await waitFor('MSE playback after a decode error',
        async () => eventsOf(served, 'dec').length > decodedBefore && (await stats(mse)).page.video.decodedFps > 0);
      check(true, 'after a decode error ends its <video>, the MSE page reconnects with a new media element and plays again');
      await recovering.close();
      const bare = await browser.newContext({ locale: 'en-US' });
      await bare.addInitScript('delete window.MediaSource; delete window.ManagedMediaSource;');
      const bareContext = await bare.newPage();
      await bareContext.goto(`http://${address}:${served.port}/play/#c=${CODE}`);
      const message = await waitFor('the no-video message', async () => {
        const text = await bareContext.textContent('#message');
        return /cannot show the video/.test(text) && text;
      });
      check(await bareContext.isDisabled('#connect') && message.includes('not a secure context, no MediaSource'), `without any rung the page says "${message}"`);
      await bare.close();
    }

    const unexpected = failures.filter(text => !/Failed to load resource|ERR_CONNECTION_RESET|ERR_EMPTY_RESPONSE|network error/i.test(text));
    check(!unexpected.length, `no unexpected page errors${unexpected.length ? `: ${unexpected.join(' | ')}` : ''}`);
    log('all checks passed');
  } finally {
    await browser.close();
    for (const server of phoneServers) await server.close();
    pageServer.closeAllConnections();
    pageServer.close();
  }
}

main().catch(error => {
  console.error(`[e2e] FAILED: ${error.message}`);
  process.exitCode = 1;
});
