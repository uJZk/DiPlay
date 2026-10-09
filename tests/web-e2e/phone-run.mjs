// SPDX-License-Identifier: GPL-3.0-only
// End-to-end check of the real phone side: RealPhone.java runs the app's BrowserLinkServer, WebVideoHub,
// VideoTeeMediaSink and WebMediaSink (compiled :shared classes) with an H.264 clip, serves the page from a page
// directory like the app does from its assets, and headless Chromium opens it. Not part of CI; see README.md.
//
// This Chromium cannot decode H.264, so the first two runs check the transport and the page's message, and the third
// replaces VideoDecoder inside the decoder worker with a stand-in that checks each chunk and outputs a plain frame.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { connect } from 'node:net';
import { homedir, networkInterfaces, tmpdir } from 'node:os';
import { join } from 'node:path';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';

const { chromium } = createRequire(import.meta.url)('playwright-core'); // resolved through NODE_PATH

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const CODE = '271828';
const WIDTH = 1280, HEIGHT = 720, FPS = 30;
const CHROMIUM = process.env.CHROMIUM ?? ['/opt/pw-browsers/chromium-1194/chrome-linux/chrome'].find(existsSync);
const JAVA = process.env.JAVA ?? 'java';
const FFMPEG = process.env.FFMPEG ?? 'ffmpeg';
const PAGE_DIR = process.env.PAGE_DIR ?? join(ROOT, 'site/play');
const SHARED_CLASSES = process.env.SHARED_CLASSES ?? join(ROOT, 'shared/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes');
/** The page's served policy, as BrowserLinkServer.PAGE_POLICY sends it. */
const PAGE_POLICY = "default-src 'self'; connect-src 'self' http:; media-src 'self' blob:; img-src 'self' data:; style-src 'self'; " +
  "script-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

// A VideoDecoder stand-in for the decoder worker: it checks the chunks the phone sent and draws a plain frame for each.
const DECODER_STAND_IN = `(() => {
  const info = { chunks: 0, keys: 0, firstKeyTypes: null, firstDeltaTypes: null, badStartCodes: 0, failures: 0, configs: [] };
  let failNext = false;
  self.addEventListener('message', event => { if (event.data && event.data.type === 'stand-in-fail') failNext = true; });
  const nalTypes = data => {
    const types = [];
    for (let i = 0; i + 4 < data.length; i++) {
      if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 0 && data[i + 3] === 1) { types.push(data[i + 4] & 0x1f); i += 3; }
    }
    return types;
  };
  setInterval(() => self.postMessage({ type: 'stand-in', info: JSON.parse(JSON.stringify(info)) }), 200);
  self.VideoDecoder = class {
    static async isConfigSupported(config) { return { supported: /^avc1\\./.test(config.codec), config }; }
    constructor({ output, error }) { this.output = output; this.error = error; this.state = 'unconfigured'; this.decodeQueueSize = 0; }
    configure(config) {
      info.configs.push(config.codec + ' ' + config.codedWidth + 'x' + config.codedHeight);
      this.state = 'configured';
      this.canvas = new OffscreenCanvas(config.codedWidth, config.codedHeight);
      this.context = this.canvas.getContext('2d');
    }
    decode(chunk) {
      if (this.state !== 'configured') throw new DOMException('not configured', 'InvalidStateError');
      const data = new Uint8Array(chunk.byteLength);
      chunk.copyTo(data);
      info.chunks++;
      if (data[0] !== 0 || data[1] !== 0 || data[2] !== 0 || data[3] !== 1) info.badStartCodes++;
      if (chunk.type === 'key') { info.keys++; if (!info.firstKeyTypes) info.firstKeyTypes = nalTypes(data); }
      else if (!info.firstDeltaTypes) info.firstDeltaTypes = nalTypes(data);
      if (failNext) {
        failNext = false;
        info.failures++;
        this.state = 'closed';
        setTimeout(() => this.error(new DOMException('stand-in decode failure', 'EncodingError')));
        return;
      }
      this.decodeQueueSize++;
      setTimeout(() => {
        this.decodeQueueSize--;
        if (this.state !== 'configured') return;
        this.context.fillStyle = 'hsl(' + (info.chunks * 7 % 360) + ' 60% 40%)';
        this.context.fillRect(0, 0, this.canvas.width, this.canvas.height);
        this.output(new VideoFrame(this.canvas, { timestamp: chunk.timestamp }));
      });
    }
    flush() { return Promise.resolve(); }
    reset() { this.decodeQueueSize = 0; this.state = 'unconfigured'; }
    close() { this.state = 'closed'; }
  };
})();
`;
// Main-thread side of the stand-in: keeps the workers and their stand-in reports.
const WATCH_WORKERS = `(() => {
  const Native = window.Worker;
  window.__workers = [];
  window.__standIn = null;
  window.Worker = class extends Native {
    constructor(...args) {
      super(...args);
      window.__workers.push(this);
      this.addEventListener('message', event => { if (event.data && event.data.type === 'stand-in') window.__standIn = event.data.info; });
    }
  };
})();`;

function log(message) {
  console.log(`[phone-e2e] ${message}`);
}

function check(condition, message) {
  if (!condition) throw new Error(message);
  log(`ok - ${message}`);
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

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

/** The Kotlin standard library jar of the version the build uses, from the Gradle cache. */
function kotlinStdlib() {
  if (process.env.KOTLIN_STDLIB) return process.env.KOTLIN_STDLIB;
  const version = /^kotlin\s*=\s*"([^"]+)"/m.exec(readFileSync(join(ROOT, 'gradle/libs.versions.toml'), 'utf8'))?.[1];
  const base = join(process.env.GRADLE_USER_HOME ?? join(homedir(), '.gradle'), 'caches/modules-2/files-2.1/org.jetbrains.kotlin/kotlin-stdlib', version ?? '');
  for (const hash of existsSync(base) ? readdirSync(base) : []) {
    const jar = join(base, hash, `kotlin-stdlib-${version}.jar`);
    if (existsSync(jar)) return jar;
  }
  throw new Error(`kotlin-stdlib ${version} not found under ${base}; set KOTLIN_STDLIB`);
}

/** A three-second 1280×720 H.264 clip (Annex-B, AUDs, an IDR every second), or null without ffmpeg/libx264. */
function encodeClip(dir) {
  const clip = join(dir, 'clip.h264');
  const result = spawnSync(FFMPEG, ['-hide_banner', '-loglevel', 'error', '-f', 'lavfi', '-i', `testsrc2=size=${WIDTH}x${HEIGHT}:rate=${FPS}`,
    '-t', '3', '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency', '-b:v', '1500k', '-pix_fmt', 'yuv420p',
    '-x264-params', `aud=1:keyint=${FPS}:min-keyint=${FPS}:scenecut=0`, '-bsf:v', 'h264_mp4toannexb', '-f', 'h264', clip]);
  if (result.status !== 0) {
    log(`ffmpeg with libx264 unavailable (${result.error?.message ?? result.stderr.toString().trim()}); hand-made SPS/PPS and slices`);
    return null;
  }
  return clip;
}

/** Starts RealPhone.java and collects its JSON events. */
async function startPhone(clip) {
  for (const path of [SHARED_CLASSES, PAGE_DIR]) if (!existsSync(path)) throw new Error(`${path} is missing; build :shared first (see README.md)`);
  const args = ['-cp', `${SHARED_CLASSES}:${kotlinStdlib()}`, join(ROOT, 'tests/web-e2e/RealPhone.java'), '--page', PAGE_DIR, '--port', '0',
    '--bind', '0.0.0.0', '--code', CODE, '--width', String(WIDTH), '--height', String(HEIGHT), '--fps', String(FPS)];
  if (clip) args.push('--clip', clip);
  const child = spawn(JAVA, args, { stdio: ['pipe', 'pipe', 'pipe'] });
  const events = [], stderr = [];
  createInterface({ input: child.stdout }).on('line', line => {
    try {
      events.push(JSON.parse(line));
    } catch (_) {
      stderr.push(line);
    }
  });
  createInterface({ input: child.stderr }).on('line', line => { if (!line.startsWith('Picked up JAVA_TOOL_OPTIONS')) stderr.push(line); });
  const exited = new Promise(resolve => child.on('exit', code => resolve(code)));
  const listening = await Promise.race([
    waitFor('RealPhone to listen', () => events.find(event => event.event === 'listening'), 60000),
    exited.then(code => { throw new Error(`RealPhone exited with ${code}: ${stderr.join('\n')}`); }),
  ]);
  return {
    port: listening.port,
    events,
    stderr,
    command: line => child.stdin.write(`${line}\n`),
    since: (mark, name) => events.slice(mark).filter(event => event.event === name),
    logs: mark => events.slice(mark).filter(event => event.event === 'log').map(event => event.message),
    async close() {
      child.stdin.write('quit\n');
      await Promise.race([exited, sleep(3000)]);
      child.kill();
    },
  };
}

function lanAddress() {
  return Object.values(networkInterfaces()).flat().find(entry => entry.family === 'IPv4' && !entry.internal)?.address ?? null;
}

const stats = page => page.evaluate(() => window.tiplayStats);

/** Collects what a page does: its requests to the phone and its console problems. */
function watch(page) {
  const seen = { requests: [], problems: [], responses: [] };
  page.on('request', request => seen.requests.push({ url: request.url(), method: request.method(), headers: request.headers() }));
  page.on('response', response => seen.responses.push(`${response.status()} ${new URL(response.url()).pathname}`));
  page.on('requestfailed', request => seen.responses.push(`failed ${new URL(request.url()).pathname} ${request.failure()?.errorText}`));
  page.on('console', message => {
    if (message.type() === 'error' || /Content Security Policy|Refused to/i.test(message.text())) seen.problems.push(message.text());
  });
  page.on('pageerror', error => seen.problems.push(`pageerror: ${error.message}`));
  return seen;
}

/** The checks every run shares: own origin only, the code only in /video, no Referer, no CSP or script errors. */
async function checkRequests(page, seen, origin, label) {
  const counts = (await stats(page)).phone?.link?.counts ?? {};
  check(!counts.busy, `${label}: the phone never answered busy (${JSON.stringify(counts)})`);
  const foreign = seen.requests.filter(request => new URL(request.url).origin !== origin);
  check(!foreign.length, `${label}: every request goes to the phone's own origin ${origin}${foreign.length ? `, not ${foreign[0].url}` : ''}`);
  const leaks = seen.requests.filter(request => request.url.includes(CODE) && new URL(request.url).pathname !== '/video');
  check(!leaks.length, `${label}: only /video carries the pairing code in its URL${leaks.length ? `, not ${leaks[0].url}` : ''}`);
  const referred = seen.requests.filter(request => request.headers.referer);
  check(!referred.length, `${label}: no request sends a Referer${referred.length ? ` (${referred[0].url})` : ''}`);
  const problems = seen.problems.filter(text => !/Failed to load resource|ERR_CONNECTION_RESET|ERR_EMPTY_RESPONSE|network error/i.test(text));
  check(!problems.length, `${label}: no CSP violations or page errors${problems.length ? `: ${problems.join(' | ')}` : ''}`);
}

/** Opens `${origin}/#c=CODE`: the redirect keeps the fragment, the page stores the code and drops the fragment. */
async function openPage(context, origin, label) {
  const page = await context.newPage();
  const seen = watch(page);
  await page.goto(`${origin}/#c=${CODE}`);
  const state = await page.evaluate(() => ({ href: location.href, hash: location.hash, link: localStorage.getItem('tiplay.link') }));
  check(state.href === `${origin}/play/` && state.hash === '',
    `${label}: / redirects to /play/ and the page removes #c= from the address (${state.href})`);
  const stored = JSON.parse(state.link ?? 'null');
  check(stored?.code === CODE && stored?.host === new URL(origin).host, `${label}: the code is stored and the phone address is the page's own (${stored?.host})`);
  return { page, seen };
}

async function checkAssets(origin) {
  const root = await fetch(`${origin}/`, { redirect: 'manual' });
  check(root.status === 302 && root.headers.get('location') === '/play/', `GET / answers 302 to ${root.headers.get('location')}`);
  for (const [path, type] of [['/play/', 'text/html; charset=utf-8'], ['/play/app.js', 'text/javascript; charset=utf-8'],
    ['/play/sw.js', 'text/javascript; charset=utf-8'], ['/play/style.css', 'text/css; charset=utf-8'], ['/play/icon.svg', 'image/svg+xml']]) {
    const response = await fetch(`${origin}${path}`);
    const headers = Object.fromEntries(response.headers);
    check(response.status === 200 && headers['content-type'] === type && headers['cache-control'] === 'no-cache' &&
      headers['x-content-type-options'] === 'nosniff' && headers['content-security-policy'] === PAGE_POLICY,
    `${path} is served as ${headers['content-type']} with no-cache, nosniff and the page policy`);
  }
  const hello = await fetch(`${origin}/hello`);
  check(hello.headers.get('cache-control') === 'no-store' && !hello.headers.get('content-security-policy') &&
    JSON.stringify(await hello.json()) === '{"app":"TiPlay","protocol":1}', '/hello keeps no-store and names TiPlay protocol 1');
  const foreignHost = await rawStatus(origin, 'GET /play/ HTTP/1.1\r\nHost: tiplay.example\r\nConnection: close\r\n\r\n');
  check(foreignHost === 421, `a page request for Host tiplay.example answers ${foreignHost} (DNS-rebinding defence)`);
}

/** Sends a raw request and returns the response status. */
function rawStatus(origin, request) {
  const { hostname, port } = new URL(origin);
  return new Promise((resolve, reject) => {
    let text = '';
    const socket = connect(Number(port), hostname, () => socket.end(request));
    socket.setEncoding('latin1');
    socket.on('data', chunk => { text += chunk; });
    socket.on('end', () => resolve(Number(/^HTTP\/1\.1 (\d{3})/.exec(text)?.[1])));
    socket.on('error', reject);
  });
}

/** Replaces VideoDecoder in the decoder worker with the stand-in, and keeps the workers for the stand-in's reports. */
async function useDecoderStandIn(context) {
  await context.addInitScript(WATCH_WORKERS);
  await context.route('**/play/decoder-worker.js', async route => {
    const response = await route.fetch();
    await route.fulfill({ response, body: `${DECODER_STAND_IN}\n${await response.text()}` });
  });
}

/** A tap at x/y of the letterboxed picture must reach onTouch as slot 0 down then up, with slot 1 idle, normalized. */
async function checkTap(page, phone, x, y, label) {
  const rect = await page.evaluate(() => JSON.parse(JSON.stringify(document.getElementById('touch').getBoundingClientRect())));
  const scale = Math.min(rect.width / WIDTH, rect.height / HEIGHT);
  const left = rect.left + (rect.width - WIDTH * scale) / 2, top = rect.top + (rect.height - HEIGHT * scale) / 2;
  const mark = phone.events.length;
  await page.touchscreen.tap(left + x * WIDTH * scale, top + y * HEIGHT * scale);
  const touches = await waitFor('a touch down and up', () => {
    const all = phone.since(mark, 'touch').map(event => event.contacts);
    return all.length >= 2 && all;
  });
  const near = (contact, down) => contact[0] === 0 && Math.abs(contact[1] - x) < 0.01 && Math.abs(contact[2] - y) < 0.01 && contact[3] === down;
  const idle = contact => contact[0] === 1 && contact[3] === false;
  check(touches[0].length === 2 && near(touches[0][0], true) && idle(touches[0][1]) &&
    touches.at(-1).length === 2 && near(touches.at(-1)[0], false) && idle(touches.at(-1)[1]),
  `${label}: a tap at ${x}/${y} reaches onTouch as ${JSON.stringify(touches[0])} then ${JSON.stringify(touches.at(-1))}`);
}

/** The page from another origin, as the public HTTPS page is: a static server for the page directory. */
function startPageHost() {
  const types = { html: 'text/html', js: 'text/javascript', css: 'text/css', svg: 'image/svg+xml' };
  const server = createServer((req, res) => {
    const name = new URL(req.url, 'http://page').pathname.replace(/^\/play\//, '') || 'index.html';
    const file = join(PAGE_DIR, name);
    if (name.includes('/') || !existsSync(file)) return res.writeHead(404).end();
    res.writeHead(200, { 'Content-Type': types[name.split('.').pop()] ?? 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(readFileSync(file));
  });
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server)));
}

/** Stats keys that TeslaBrowserLink.statsLine puts in the session log (its STATS_FIELDS). */
function statsLogKeys() {
  const source = readFileSync(join(ROOT, 'common/src/main/java/com/shilapi/xcertplay/TeslaBrowserLink.kt'), 'utf8');
  const block = /STATS_FIELDS = listOf\(([\s\S]*?)\n\s*\)/.exec(source)?.[1] ?? '';
  return [...block.matchAll(/"[^"]+" to "([^"]+)"/g)].map(match => match[1]);
}

async function main() {
  const dir = mkdtempSync(join(tmpdir(), 'tiplay-phone-e2e-'));
  const clip = encodeClip(dir);
  const phone = await startPhone(clip);
  const clipInfo = phone.events.find(event => event.event === 'clip');
  log(`RealPhone listens on port ${phone.port} with ${clipInfo.units} access units (${clipInfo.source}), page ${PAGE_DIR}`);
  const config = await waitFor('the config log', () => phone.logs(0).find(text => text.startsWith('Web video: config epoch=1 ')));
  const codec = /codec=(\S+)/.exec(config)[1];
  // Chrome counts this container's LAN address as public; a hotspot address is local, so make it local here too.
  const address = lanAddress();
  const browser = await chromium.launch({ executablePath: CHROMIUM,
    args: address ? [`--ip-address-space-overrides=${address}:${phone.port}=local`] : [] });
  const sessions = new Set();
  try {
    const local = `http://127.0.0.1:${phone.port}`;
    await checkAssets(local);

    // 1. Secure context (loopback): the Tesla path. This Chromium cannot decode H.264, so the page must say so.
    {
      const context = await browser.newContext({ viewport: { width: 1000, height: 700 }, deviceScaleFactor: 1.5, hasTouch: true, locale: 'en-US' });
      const mark = phone.events.length;
      const { page, seen } = await openPage(context, local, 'loopback');
      const opened = await waitFor('the video stream', () => phone.logs(mark).find(text => text.startsWith('Browser link: video stream opened')));
      check(true, `the page opened /video with the code (${opened})`);
      const first = await waitFor('the config record', async () => {
        const value = await stats(page);
        return value.page.video.codec && value;
      });
      check(first.page.link.path === 'worker', `a secure context takes the Tesla path (${first.page.link.path}${first.page.link.path === 'worker' ? '' :
        `; ${first.page.link.fallback}; responses ${seen.responses.join(', ')}; phone ${JSON.stringify(first.phone?.link?.counts)}`})`);
      check(first.page.video.codec === codec && first.page.video.width === WIDTH && first.page.video.height === HEIGHT,
        `the page got the config record: ${first.page.video.codec} ${first.page.video.width}x${first.page.video.height}`);
      const status = await waitFor('the unsupported message', async () => {
        const text = await page.textContent('#status');
        return /cannot decode/.test(text) && text;
      });
      check(status === 'This browser cannot decode H.264.' && (await stats(page)).page.link.unsupported === codec,
        `without an H.264 decoder the page says "${status}"`);
      const rect = await page.evaluate(() => JSON.parse(JSON.stringify(document.getElementById('stage').getBoundingClientRect())));
      const viewport = await waitFor('a viewport', () => phone.since(mark, 'viewport').at(-1));
      check(viewport.cw === Math.round(rect.width) && viewport.ch === Math.round(rect.height) && viewport.dpr === 1.5 &&
        viewport.w === Math.round(rect.width * 1.5 / 2) * 2 && viewport.h === Math.round(rect.height * 1.5 / 2) * 2,
      `onViewport got ${viewport.w}x${viewport.h} (css ${viewport.cw}x${viewport.ch}, dpr ${viewport.dpr}) for the video area`);
      const beat = phone.events.length;
      await sleep(2000);
      const beats = phone.since(beat, 'control').length;
      check(beats >= 4 && beats <= 8, `${beats} /control requests in 2 s (one heartbeat every 400 ms)`);
      const st = await waitFor('a stats event', () => phone.since(mark, 'stats').at(-1), 8000);
      check(st.values['link.path'] === 'worker' && st.values['link.unsupported'] === codec,
        `onBrowserStats got the page's stats with path and the unsupported codec (${Object.keys(st.values).length} fields)`);
      const registered = await waitFor('the service worker', () => page.evaluate(() =>
        navigator.serviceWorker.getRegistration().then(registration => Boolean(registration?.active))), 8000).catch(() => false);
      check(registered, 'the service worker registers under the served policy');
      const video = seen.requests.find(request => new URL(request.url).pathname === '/video');
      sessions.add(new URL(video.url).searchParams.get('s'));
      await checkRequests(page, seen, local, 'loopback');
      await context.close();
    }

    // 2. Over the LAN address the page is not a secure context: the MSE rung, which cannot play H.264 here either.
    if (!address) log('skipped the LAN check: no non-loopback IPv4 address');
    else {
      const lan = `http://${address}:${phone.port}`;
      const context = await browser.newContext({ viewport: { width: 1000, height: 700 }, locale: 'en-US' });
      const mark = phone.events.length;
      const { page, seen } = await openPage(context, lan, 'LAN');
      const value = await waitFor('the config record over MSE', async () => {
        const current = await stats(page);
        return current.page.video && current.page.link.unsupported !== undefined && (current.page.link.unsupported || current.page.live) && current;
      });
      check(value.page.link.path === 'mse' && value.page.link.fallback === 'not a secure context',
        `plain HTTP takes the ${value.page.link.path} rung (${value.page.link.fallback})`);
      const playable = await page.evaluate(type => MediaSource.isTypeSupported(`video/mp4; codecs="${type}"`), codec);
      const status = await page.textContent('#status');
      if (playable) check(value.page.live, `MSE plays ${codec}`);
      else check(status === 'This browser cannot decode H.264.', `MSE without H.264 says "${status}"`);
      check(phone.since(mark, 'control').length > 0 && phone.logs(mark).some(text => text.startsWith('Browser link: video stream opened')),
        'the LAN page talks to /video and /control at its own address');
      // The page sends targetAddressSpace "local" for a hotspot address; Chrome must accept it outside a secure context.
      const local = await page.evaluate(() => fetch('/hello', { cache: 'no-store', targetAddressSpace: 'local' }).then(response => response.status, error => error.message));
      check(local === 200, `a fetch to the phone with targetAddressSpace "local" works outside a secure context (${local})`);
      // The MSE rung plays a MediaSource object URL; the served policy must allow blob: media.
      const opened = await page.evaluate(() => new Promise(resolve => {
        const source = new MediaSource(), video = document.createElement('video');
        document.addEventListener('securitypolicyviolation', event => resolve(`blocked by ${event.violatedDirective}`), { once: true });
        source.addEventListener('sourceopen', () => resolve('open'), { once: true });
        video.src = URL.createObjectURL(source);
        setTimeout(() => resolve('timeout'), 5000);
      }));
      check(opened === 'open', `the served policy lets a <video> open a MediaSource object URL (${opened})`);
      const video = seen.requests.find(request => new URL(request.url).pathname === '/video');
      sessions.add(new URL(video.url).searchParams.get('s'));
      await checkRequests(page, seen, lan, 'LAN');
      await context.close();
    }

    // 3. A VideoDecoder stand-in in the worker: decoded frames, dec → "first frame rendered", taps, fit, key frames, restart.
    {
      const context = await browser.newContext({ viewport: { width: 1000, height: 700 }, deviceScaleFactor: 1.5, hasTouch: true,
        locale: 'en-US', serviceWorkers: 'block' });
      await useDecoderStandIn(context);
      const mark = phone.events.length;
      const { page, seen } = await openPage(context, local, 'stand-in');
      const rendered = await waitFor('"first frame rendered"', () => phone.since(mark, 'diagnostic').find(event => event.message === 'first frame rendered'));
      check(rendered.session === 1, 'the page decoded epoch 1 and its "dec" reached the session\'s diagnostic handler as "first frame rendered"');
      await waitFor('decoded frames', async () => (await stats(page)).page.video.decodedFps > 0);
      const standIn = await page.evaluate(() => window.__standIn);
      const [sps, pps, ...idr] = standIn.firstKeyTypes ?? [];
      check(sps === 7 && pps === 8 && idr.length && idr.every(type => type === 5) && standIn.firstDeltaTypes?.every(type => type === 1) &&
        standIn.badStartCodes === 0,
      `key frames arrive as SPS, PPS and IDR slices, deltas as slices, all with 4-byte start codes (${standIn.chunks} chunks, ${standIn.keys} keys)`);
      check(standIn.configs[0] === `${codec} ${WIDTH}x${HEIGHT}`, `VideoDecoder is configured as ${standIn.configs[0]}, without a description`);
      const status = await page.textContent('#status');
      check(/^H\.264 · \d+ fps$/.test(status), `the status line reads "${status}"`);

      await checkTap(page, phone, 0.25, 0.75, 'stand-in');

      // The stats fields the session log line uses exist in the page's st event.
      const st = await waitFor('a stats event while decoding', () => phone.since(mark, 'stats').find(event => event.values['video.decoded'] > 0), 8000);
      const keys = statsLogKeys();
      const missing = keys.filter(key => !(key in st.values));
      check(keys.length >= 5 && !missing.length, `the page's st event has every field of the session log line (${keys.join(', ')})${missing.length ? `; missing ${missing}` : ''}`);
      const size = await page.evaluate(() => window.tiplayStats && JSON.stringify(window.tiplayStats.page).length);
      log(`stats snapshot ${size} bytes; st event ${JSON.stringify(st.values).length} bytes of 2048`);

      // "Apply and reconnect" shows while status.fit is true and sends {"k":"fit"}.
      await page.waitForSelector('#fit:not([hidden])', { timeout: 5000 });
      const fitMark = phone.events.length;
      await page.click('#fit');
      await waitFor('onFit', () => phone.since(fitMark, 'fit').length);
      check(true, '"Apply and reconnect" reaches onFit');
      phone.command('fit-off');
      await page.waitForSelector('#fit', { state: 'hidden', timeout: 5000 });
      check(true, 'the button hides once the status no longer asks to fit');

      // A decoder error: the page asks for a key frame, the hub asks the session, and the picture comes back.
      const keyMark = phone.events.length;
      await page.evaluate(() => window.__workers.forEach(worker => worker.postMessage({ type: 'stand-in-fail' })));
      await waitFor('the decoder failure', async () => (await stats(page)).page.video.decodeErrors > 0);
      const decodedAtError = (await stats(page)).page.video.decoded;
      await waitFor('a key frame request', () => phone.since(keyMark, 'keyframe-request').length);
      await waitFor('decoding after the error', async () => (await stats(page)).page.video.decoded > decodedAtError + 10);
      check(phone.logs(keyMark).every(text => !text.startsWith('Web video: config epoch=')),
        'after a decoder error the page\'s "kf" reached the session\'s recovery handler; the repeated config kept the epoch');

      // A new CarPlay session: the old tap is closed, the old session's screen comes up late, the new one keeps the hub.
      const restartMark = phone.events.length;
      phone.command('restart');
      const second = await waitFor('"first frame rendered" for the next session',
        () => phone.since(restartMark, 'diagnostic').find(event => event.session === 2 && event.message === 'first frame rendered'));
      check(Boolean(second) && phone.logs(restartMark).filter(text => text.startsWith('Web video: stream started')).length === 1,
        'after a restart the page decoded epoch 2 of the new session; the retired session\'s late screen did not take the hub');
      const decodedAfterRestart = (await stats(page)).page.video.decoded;
      await waitFor('decoding after the restart', async () => (await stats(page)).page.video.decoded > decodedAfterRestart + 20);
      check((await stats(page)).page.video.codec === codec, 'the page keeps decoding the new session');

      const video = seen.requests.find(request => new URL(request.url).pathname === '/video');
      sessions.add(new URL(video.url).searchParams.get('s'));
      await checkRequests(page, seen, local, 'stand-in');

      // Leaving the page sends /bye.
      const byeMark = phone.events.length;
      const byes = [];
      context.on('request', request => { if (request.url().endsWith('/bye')) byes.push(`request ${request.postData()}`); });
      context.on('requestfailed', request => { if (request.url().endsWith('/bye')) byes.push(`failed ${request.failure()?.errorText}`); });
      context.on('response', response => { if (response.url().endsWith('/bye')) byes.push(`response ${response.status()}`); });
      // Playwright's request interception can drop a keepalive request of a page that is going away.
      await context.unrouteAll();
      await page.goto('about:blank');
      await waitFor('the bye', () => phone.logs(byeMark).includes('Browser link: session ended by the page'))
        .catch(error => { throw new Error(`${error.message}; the phone logged ${JSON.stringify(phone.events.slice(byeMark).filter(event => event.event !== 'asset'))}; bye ${byes}`); });
      check(true, 'leaving the page ends its session with /bye');
      await context.close();
    }

    // 4. The page from another origin, as the Tesla opens the public HTTPS page: CORS on the real server, the stand-in decodes.
    {
      const host = await startPageHost();
      const origin = `http://127.0.0.1:${host.address().port}`;
      try {
        const context = await browser.newContext({ viewport: { width: 1000, height: 700 }, deviceScaleFactor: 1.5, hasTouch: true,
          locale: 'en-US', serviceWorkers: 'block' });
        await useDecoderStandIn(context);
        const page = await context.newPage();
        const seen = watch(page);
        const cors = [];
        page.on('response', response => {
          if (new URL(response.url()).port === String(phone.port)) cors.push({ path: new URL(response.url()).pathname, headers: response.headers() });
        });
        // A new session: its first frame rendered can only come from this page ("dec" counts once per epoch).
        const mark = phone.events.length;
        phone.command('restart');
        await waitFor('the next session', () => phone.since(mark, 'restarted').length);
        await page.goto(`${origin}/play/#h=127.0.0.1:${phone.port}&c=${CODE}`);
        const decoded = await waitFor('"first frame rendered" from the other origin',
          () => phone.since(mark, 'diagnostic').find(event => event.message === 'first frame rendered'));
        check(decoded.session === 3, 'a page from another origin decodes the new session and its "dec" reaches that session');
        const echoed = cors.filter(entry => ['/hello', '/video', '/control'].includes(entry.path));
        check(echoed.length >= 3 && echoed.every(entry => entry.headers['access-control-allow-origin'] === origin &&
          /\bOrigin\b/.test(entry.headers.vary ?? '') && entry.headers['cache-control'] === 'no-store'),
        `the phone echoes the page's origin with Vary: Origin and no-store on ${[...new Set(echoed.map(entry => entry.path))].join(', ')}`);
        await checkTap(page, phone, 0.6, 0.3, 'other origin');
        const phoneRequests = seen.requests.filter(request => new URL(request.url).port === String(phone.port));
        check(phoneRequests.every(request => !request.headers.referer) &&
          phoneRequests.filter(request => request.url.includes(CODE)).every(request => new URL(request.url).pathname === '/video'),
        'other origin: only /video carries the code in its URL, and no request to the phone sends a Referer');
        const video = phoneRequests.find(request => new URL(request.url).pathname === '/video');
        sessions.add(new URL(video.url).searchParams.get('s'));
        const counts = (await stats(page)).phone?.link?.counts ?? {};
        check(!counts.busy, 'other origin: the phone never answered busy');
        await context.unrouteAll();
        await context.close();
      } finally {
        host.close();
      }
    }

    const output = JSON.stringify(phone.events.filter(event => event.event !== 'listening'));
    check(!output.includes(CODE), 'the phone side never logs or reports the pairing code');
    const leaked = [...sessions].filter(id => id && output.includes(id));
    check(sessions.size >= 2 && !leaked.length, `the phone side never logs a page session id (${sessions.size} sessions)`);
    const failures = phone.events.filter(event => event.event === 'tap-error' || (event.event === 'log' && /failed/.test(event.message) &&
      !/video stream ended reason=write failed/.test(event.message)));
    check(!failures.length, `no tap or server failures${failures.length ? `: ${JSON.stringify(failures)}` : ''}`);
    log('all checks passed');
  } finally {
    await browser.close();
    await phone.close();
    if (phone.stderr.length) log(`RealPhone stderr:\n${phone.stderr.join('\n')}`);
    rmSync(dir, { recursive: true, force: true });
  }
}

main().catch(error => {
  console.error(`[phone-e2e] FAILED: ${error.message}`);
  process.exitCode = 1;
});
