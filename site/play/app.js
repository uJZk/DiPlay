/*! SPDX-License-Identifier: GPL-3.0-only
 * Adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
 * common/src/main/assets/web/app.js at commit c1bd077 (pointer handling, viewport report, reconnect loop, stats getter).
 * Modified for TiPlay, 2026-10. Source code: https://github.com/uJZk/DiPlay
 *
 * Main thread of the TiPlay browser link (protocol v1). The video fetch starts here so it gets the page's
 * local-network treatment. On the Tesla path its body is transferred to decoder-worker.js, which draws on the
 * transferred canvas; other browsers take a later rung of the fallback ladder (link.js choosePath). */
import {
  DEFAULT_HOST, PROTOCOL, addressSpaceFor, backoffDelay, choosePath, createControlOutbox, createTouchSlots, flatStats,
  isCode, linkFromAddress, linkLocation, mapPoint, newSessionId, normalizeHost, PROBE_CODECS, supportedCodecs,
  timeoutSignal, viewportFor,
} from './link.js';
import { followAppearance } from './appearance.js';

const STRINGS = {
  en: {
    heading: 'Show CarPlay on this screen',
    intro: 'Join this car to the hotspot of the Android phone that runs TiPlay, then enter the pairing code that TiPlay shows.',
    codeLabel: 'Pairing code',
    advanced: 'Advanced',
    hostLabel: 'Phone address',
    hostHelp: 'IPv4 address and port of the phone, for example 100.109.220.253:8080.',
    connect: 'Connect',
    disconnect: 'Disconnect',
    fullscreen: 'Full screen',
    exitFullscreen: 'Exit full screen',
    stats: 'Stats',
    fit: 'Apply and reconnect',
    fitPending: 'Reconnecting…',
    source: 'Source code (GPL-3.0)',
    enterCode: 'Enter the pairing code to connect.',
    ready: 'Ready to connect.',
    stopped: 'Disconnected.',
    connecting: 'Connecting to the phone…',
    unreachable: 'The phone does not answer. Retrying in {0} s…',
    lost: 'Connection lost. Retrying in {0} s…',
    throttled: 'Too many wrong pairing codes. Retrying in {0} s…',
    waitingPhone: 'Connected. Waiting for the iPhone…',
    phoneConnecting: 'The iPhone is connecting…',
    waitingVideo: 'Waiting for the picture…',
    streaming: '{0} · {1} fps',
    badCode: 'The pairing code is wrong. Check it in TiPlay on the phone.',
    replaced: 'Another browser took over the display. Connect again to take it back.',
    protocol: 'TiPlay on the phone does not match this page. Update the app.',
    unsupported: 'This browser cannot decode {0}.',
    unsupportedHevc: 'This browser cannot decode HEVC. Turn off HEVC in TiPlay on the phone.',
    noVideo: 'This browser cannot show the video ({0}). Open the page over HTTPS in a current Chrome, Edge, Safari or Firefox.',
    invalidCode: 'Enter the 6-digit pairing code.',
    invalidHost: 'Enter an IPv4 address with port, for example 100.109.220.253:8080.',
  },
  zh: {
    heading: '在这块屏幕上显示 CarPlay',
    intro: '让车辆连接运行 TiPlay 的安卓手机热点，然后输入 TiPlay 显示的配对码。',
    codeLabel: '配对码',
    advanced: '高级',
    hostLabel: '手机地址',
    hostHelp: '手机的 IPv4 地址和端口，例如 100.109.220.253:8080。',
    connect: '连接',
    disconnect: '断开',
    fullscreen: '全屏',
    exitFullscreen: '退出全屏',
    stats: '统计',
    fit: '应用并重新连接',
    fitPending: '正在重新连接…',
    source: '源代码（GPL-3.0）',
    enterCode: '输入配对码后连接。',
    ready: '可以连接。',
    stopped: '已断开。',
    connecting: '正在连接手机…',
    unreachable: '手机没有响应，{0} 秒后重试…',
    lost: '连接中断，{0} 秒后重试…',
    throttled: '配对码错误次数过多，{0} 秒后重试…',
    waitingPhone: '已连接，等待 iPhone…',
    phoneConnecting: 'iPhone 正在连接…',
    waitingVideo: '等待画面…',
    streaming: '{0} · {1} fps',
    badCode: '配对码不正确，请在手机上的 TiPlay 中核对。',
    replaced: '另一个浏览器接管了画面。再次连接即可收回。',
    protocol: '手机上的 TiPlay 与本页不匹配，请更新应用。',
    unsupported: '此浏览器无法解码 {0}。',
    unsupportedHevc: '此浏览器无法解码 HEVC。请在手机上的 TiPlay 中关闭 HEVC。',
    noVideo: '此浏览器无法显示画面（{0}）。请用新版 Chrome、Edge、Safari 或 Firefox 通过 HTTPS 打开本页。',
    invalidCode: '请输入 6 位配对码。',
    invalidHost: '请输入带端口的 IPv4 地址，例如 100.109.220.253:8080。',
  },
};
const lang = /^zh\b/i.test(navigator.language || '') ? 'zh' : 'en';
const t = (key, ...args) => (STRINGS[lang][key] ?? STRINGS.en[key]).replace(/\{(\d)\}/g, (_, index) => args[index]);
const CODEC_NAMES = { avc1: 'H.264', avc3: 'H.264', hvc1: 'HEVC', hev1: 'HEVC', vp8: 'VP8', vp09: 'VP9', av01: 'AV1' };
const LINK_KEY = 'tiplay.link', STATS_KEY = 'tiplay.stats';
const $ = id => document.getElementById(id);
const stage = $('stage'), touch = $('touch');
const codecName = codec => CODEC_NAMES[String(codec).split('.')[0]] ?? codec;

document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
for (const element of document.querySelectorAll('[data-i18n]')) element.textContent = t(element.dataset.i18n);

// The copy of this page that the phone serves over plain HTTP talks to its own origin.
const pageHost = location.protocol === 'http:' ? normalizeHost(location.host) : null;

function loadLink() {
  try {
    const saved = JSON.parse(localStorage.getItem(LINK_KEY) || 'null');
    return { host: normalizeHost(saved?.host) ?? pageHost ?? DEFAULT_HOST, code: isCode(saved?.code) ? saved.code : null };
  } catch (_) {
    return { host: pageHost ?? DEFAULT_HOST, code: null };
  }
}

function saveLink() {
  try { localStorage.setItem(LINK_KEY, JSON.stringify(link)); } catch (_) {}
}

let link = loadLink();
const addressed = linkFromAddress(location.search, location.hash, link, pageHost);
if (addressed) {
  link = addressed;
  saveLink();
}
// `?t=` keeps a phone other than the default in bookmarks; the pairing code stays out of history entries and copied URLs.
const showLink = () => history.replaceState(null, '', linkLocation(location.pathname, location.search, link.host, pageHost));
showLink();

const slots = createTouchSlots();
let session = null, active = false, phase = 'ready', notice = null;
let attempt = 0, reconnects = 0, retryTimer = null, retryAt = 0, streamId = 0, videoAbort = null, lastError = null;
let config = null, live = false, status = null, unsupported = null, videoStats = {};
let outbox = null, heartbeatTimer = null, statsTimer = null, viewportTimer = null, lastViewport = '';
const reportAppearance = followAppearance(window.matchMedia('(prefers-color-scheme: dark)'), () => outbox);
let movePending = false, fitRequestedAt = 0, wakeLock = null, resumeOnShow = false;
let statsVisible = false;
try { statsVisible = localStorage.getItem(STATS_KEY) === '1'; } catch (_) {}

// The video path: feature detection only, never the user agent. `sink` opens response bodies on the chosen path.
let videoPath = { path: 'pending', reason: null }, sink = null, probedCodecs = '';
const pathReady = setUpVideo().catch(error => {
  videoPath = { path: 'none', reason: String(error?.message ?? error) };
});
const codecsReady = pathReady.then(async () => {
  let names = [];
  if (videoPath.decode === 'worker' || videoPath.decode === 'main') names = await supportedCodecs(config => VideoDecoder.isConfigSupported(config));
  else if (videoPath.decode === 'mse') {
    const type = window.ManagedMediaSource ?? window.MediaSource;
    names = Object.keys(PROBE_CODECS).filter(name => type.isTypeSupported(`video/mp4; codecs="${PROBE_CODECS[name]}"`));
  }
  probedCodecs = names.join(',');
  return probedCodecs;
});

function streamsTransferable() {
  try {
    const stream = new ReadableStream(), channel = new MessageChannel();
    channel.port1.postMessage(stream, [stream]);
    channel.port1.close();
    return true;
  } catch (_) {
    return false;
  }
}

/** Starts the decoder worker; resolves with it and whether it has VideoDecoder, or null when module workers fail. */
function startWorker() {
  return new Promise(resolve => {
    let worker;
    try {
      worker = new Worker(new URL('decoder-worker.js', import.meta.url), { type: 'module' });
    } catch (_) {
      resolve(null);
      return;
    }
    const fail = () => {
      clearTimeout(timer);
      worker.terminate();
      resolve(null);
    };
    // Generous: on a first visit over a slow link the worker's modules still have to download.
    const timer = setTimeout(fail, 15000);
    worker.onerror = fail;
    worker.onmessage = ({ data }) => {
      if (data.type !== 'ready') return;
      clearTimeout(timer);
      worker.onerror = null;
      worker.onmessage = null;
      resolve({ worker, videoDecoder: data.videoDecoder });
    };
  });
}

async function setUpVideo() {
  const features = {
    secureContext: window.isSecureContext === true,
    videoDecoder: typeof VideoDecoder === 'function',
    workerVideoDecoder: false,
    offscreenCanvas: typeof HTMLCanvasElement.prototype.transferControlToOffscreen === 'function',
    transferableStreams: streamsTransferable(),
    mediaSource: Boolean(window.ManagedMediaSource || window.MediaSource),
  };
  const started = features.secureContext && features.videoDecoder ? await startWorker() : null;
  features.workerVideoDecoder = Boolean(started?.videoDecoder);
  videoPath = choosePath(features);
  const path = videoPath;
  if (started && path.decode !== 'worker') started.worker.terminate();
  const canvas = $('screen');
  if (path.decode === 'worker') {
    const { worker } = started;
    let presenter = null;
    if (path.draw === 'worker') {
      const offscreen = canvas.transferControlToOffscreen();
      worker.postMessage({ type: 'canvas', canvas: offscreen }, [offscreen]);
    } else {
      presenter = (await import('./video.js')).createFramePresenter(canvas);
    }
    worker.onmessage = ({ data }) => {
      if (data.type === 'frame') presenter.present(data.frame, data.config);
      else if (data.type === 'stats') onVideoMessage({ type: 'stats', stats: { ...data.stats, ...presenter?.stats() } });
      else onVideoMessage(data);
    };
    worker.onerror = () => stop('noVideo', 'decoder worker failed');
    sink = {
      open(id, body) {
        if (path.feed === 'stream') worker.postMessage({ type: 'stream', id, stream: body }, [body]);
        else pumpChunks(worker, id, body);
      },
      stop: () => worker.postMessage({ type: 'stop' }),
      resize: (width, height) => (presenter ? presenter.resize(width, height) : worker.postMessage({ type: 'resize', width, height })),
    };
  } else if (path.decode === 'main') {
    const { createDecoderPipeline, createFramePresenter } = await import('./video.js');
    const presenter = createFramePresenter(canvas);
    const pipeline = createDecoderPipeline({ post: onVideoMessage, present: presenter.present });
    setInterval(() => onVideoMessage({ type: 'stats', stats: { ...pipeline.stats(), ...presenter.stats() } }), 1000);
    sink = { open: pipeline.read, stop: pipeline.stop, resize: presenter.resize };
  } else if (path.decode === 'mse') {
    const video = $('video');
    canvas.hidden = true;
    video.hidden = false;
    const player = (await import('./mse.js')).createMsePlayer(video, { post: onVideoMessage });
    setInterval(() => onVideoMessage({ type: 'stats', stats: player.stats() }), 1000);
    sink = { open: player.read, stop: player.stop, resize: () => {} };
  }
}

/** Streams that cannot be transferred: read the body here and transfer each chunk's buffer to the worker. */
async function pumpChunks(worker, id, body) {
  const reader = body.getReader();
  worker.postMessage({ type: 'feed', id });
  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (id !== streamId) {
        reader.cancel().catch(() => {});
        return;
      }
      if (done) break;
      // Transfer only a buffer that holds just this chunk; anything else is copied first.
      const chunk = value.byteOffset === 0 && value.byteLength === value.buffer.byteLength ? value : value.slice();
      worker.postMessage({ type: 'chunk', id, chunk }, [chunk.buffer]);
    }
    worker.postMessage({ type: 'feed-end', id });
  } catch (_) {
    worker.postMessage({ type: 'feed-end', id, error: 'network' });
  }
}

function phoneFetch(route, init = {}) {
  return fetch(`http://${link.host}${route}`, {
    cache: 'no-store', credentials: 'omit', referrerPolicy: 'no-referrer', targetAddressSpace: addressSpaceFor(link.host), ...init,
  });
}

async function httpError(response) {
  let word = null;
  try { word = (await response.json()).error; } catch (_) {}
  return Object.assign(new Error(word || `HTTP ${response.status}`), { status: response.status });
}

function start() {
  if (active || videoPath.path === 'none') return;
  // A fresh session id per start lets "Connect" take the display back after another browser replaced this one.
  session = newSessionId();
  active = true;
  notice = null;
  attempt = 0;
  reconnects = 0;
  lastError = null;
  status = null;
  live = false;
  lastViewport = '';
  fitRequestedAt = 0;
  $('panel').hidden = true;
  stage.hidden = false;
  outbox = createControlOutbox({ send: postControl, heldContacts: () => slots.contacts(), onStatus: applyStatus, onError: controlFailed });
  reportAppearance();
  heartbeatTimer = setInterval(() => outbox.heartbeat(), 400);
  statsTimer = setInterval(() => outbox.push({ k: 'st', v: flatStats(pageStats()) }, 'latest'), 5000);
  outbox.heartbeat();
  resizeCanvas();
  scheduleViewport();
  keepScreenOn();
  connectVideo();
}

function stop(key = null, ...args) {
  notice = key ? { key, args } : null;
  active = false;
  phase = 'ready';
  streamId++;
  clearTimeout(retryTimer);
  clearInterval(heartbeatTimer);
  clearInterval(statsTimer);
  videoAbort?.abort();
  sink?.stop();
  cancelMove();
  slots.releaseAll();
  outbox?.close();
  outbox = null;
  status = null;
  live = false;
  wakeLock?.then(lock => lock?.release()).catch(() => {});
  wakeLock = null;
  $('panel').hidden = false;
  stage.hidden = true;
  $('message').textContent = key && key !== 'stopped' ? t(key, ...args) : '';
  render();
}

function sendBye() {
  phoneFetch('/bye', { method: 'POST', body: JSON.stringify({ c: link.code, s: session }), keepalive: true }).catch(() => {});
}

async function connectVideo() {
  const id = ++streamId;
  clearTimeout(retryTimer);
  videoAbort?.abort();
  live = false;
  phase = 'connecting';
  render();
  const abort = videoAbort = new AbortController();
  const timer = setTimeout(() => abort.abort(), 6000); // until the response headers arrive
  try {
    const helloResponse = await phoneFetch('/hello', { signal: abort.signal });
    if (!helloResponse.ok) throw await httpError(helloResponse);
    const hello = await helloResponse.json();
    if (id !== streamId) return;
    if (hello.app !== 'TiPlay' || hello.protocol !== PROTOCOL) {
      stop('protocol');
      return;
    }
    const codecs = await codecsReady;
    if (id !== streamId) return;
    if (!sink) {
      stop('noVideo', videoPath.reason);
      return;
    }
    const response = await phoneFetch(`/video?c=${link.code}&s=${session}&codecs=${codecs}`, { signal: abort.signal });
    clearTimeout(timer);
    if (id !== streamId) {
      response.body?.cancel().catch(() => {});
      return;
    }
    if (!response.ok) throw await httpError(response);
    sink.open(id, response.body);
    phase = 'waiting';
    // A phone that restarted while this page stayed open has forgotten the viewport; repeat it once per stream.
    lastViewport = '';
    scheduleViewport();
    render();
  } catch (error) {
    clearTimeout(timer);
    if (id !== streamId) return;
    if (error.status === 401) stop('badCode');
    else if (error.status === 409) stop('replaced');
    else {
      lastError = error;
      scheduleRetry(error.status === 429 ? 30000 : backoffDelay(attempt++));
    }
  }
}

function streamEnded(reason) {
  live = false;
  releaseTouches();
  if (reason === 'replaced') {
    stop('replaced');
    return;
  }
  lastError = null;
  scheduleRetry(backoffDelay(attempt++));
}

function scheduleRetry(delay) {
  phase = 'retry';
  retryAt = Date.now() + delay;
  clearTimeout(retryTimer);
  retryTimer = setTimeout(() => {
    reconnects++;
    connectVideo();
  }, delay);
  render();
}

async function postControl(q, events) {
  const response = await phoneFetch('/control', {
    method: 'POST', body: JSON.stringify({ c: link.code, s: session, q, e: events }), signal: timeoutSignal(3000),
  });
  if (!response.ok) throw await httpError(response);
  return response.json();
}

function applyStatus(next) {
  status = next;
  if (!next.fit || Date.now() - fitRequestedAt > 15000) fitRequestedAt = 0;
  render();
}

function controlFailed(error) {
  if (error.status === 401) stop('badCode');
  else if (error.status === 409) stop('replaced');
}

function onVideoMessage(message) {
  if (message.type === 'alive' && message.id === streamId) attempt = 0;
  else if (message.type === 'config') {
    config = message.config;
    unsupported = null;
  } else if (message.type === 'unsupported') {
    unsupported = message.codec;
    live = false;
  } else if (message.type === 'decoded') {
    live = true;
    outbox?.push({ k: 'dec', ep: message.epoch }, 'latest'); // repeated if its POST fails: the phone's connection proof
    keepScreenOn();
  } else if (message.type === 'keyframe') outbox?.push({ k: 'kf' }, 'latest');
  else if (message.type === 'end' && message.id === streamId) streamEnded(message.reason);
  else if (message.type === 'stats') videoStats = message.stats;
  render();
}

function render() {
  $('disconnect').hidden = !active;
  $('stats-toggle').hidden = !active;
  $('stats-toggle').setAttribute('aria-pressed', String(statsVisible));
  $('fit').hidden = !(active && status?.fit === true);
  $('fit').disabled = fitRequestedAt > 0;
  $('fit').textContent = t(fitRequestedAt ? 'fitPending' : 'fit');
  $('status').textContent = statusText();
  $('stats').hidden = !(active && statsVisible);
  if (active && statsVisible) {
    $('stats').textContent = Object.entries(flatStats(snapshot(), Infinity)).map(([key, value]) => `${key} ${value}`).join('\n');
  }
}

function statusText() {
  if (!active) return notice ? t(notice.key, ...notice.args) : t(link.code ? 'ready' : 'enterCode');
  if (unsupported) return /^(hvc1|hev1)\b/.test(unsupported) ? t('unsupportedHevc') : t('unsupported', codecName(unsupported));
  if (phase === 'connecting') return t('connecting');
  if (phase === 'retry') {
    const seconds = Math.max(1, Math.ceil((retryAt - Date.now()) / 1000));
    return t(lastError?.status === 429 ? 'throttled' : lastError ? 'unreachable' : 'lost', seconds);
  }
  if (status?.state === 'idle') return t('waitingPhone');
  if (status?.state === 'connecting') return t('phoneConnecting');
  if (!live) return t('waitingVideo');
  return t('streaming', codecName(config?.codec), Math.round(videoStats.decodedFps ?? 0));
}

function pageStats() {
  return {
    link: { path: videoPath.path, fallback: videoPath.reason, codecs: probedCodecs, phase, attempt, reconnects, live, unsupported },
    video: videoStats,
    control: outbox?.stats() ?? null,
  };
}

/** Everything the stats overlay shows; never the pairing code or the session id. */
function snapshot() {
  const { phone = null, ...rest } = status ?? {};
  return { page: pageStats(), status: status ? rest : null, phone };
}
Object.defineProperty(window, 'tiplayStats', { configurable: true, get: () => JSON.parse(JSON.stringify(snapshot())) });

// Touch: one overlay element over the picture, which keeps the aspect of the negotiated CarPlay canvas.
function cancelMove() {
  movePending = false;
}

function releaseTouches() {
  cancelMove();
  const lifted = slots.releaseAll();
  if (lifted.length) outbox?.push({ k: 't', p: lifted });
}

/** Returns true when the pointer took a slot. */
function pointerDown(pointerId, clientX, clientY) {
  if (!live || !config || !outbox) return false;
  const point = mapPoint(clientX, clientY, touch.getBoundingClientRect(), config.width, config.height);
  if (!point || !slots.down(pointerId, point)) return false;
  cancelMove();
  outbox.push({ k: 't', p: slots.contacts() });
  return true;
}

function pointerMove(pointerId, clientX, clientY) {
  if (!slots.has(pointerId) || !config) return;
  const point = mapPoint(clientX, clientY, touch.getBoundingClientRect(), config.width, config.height, true);
  if (!point) return;
  slots.move(pointerId, point);
  if (movePending) return;
  movePending = true;
  requestAnimationFrame(() => {
    if (!movePending) return;
    movePending = false;
    if (slots.size) outbox?.push({ k: 't', p: slots.contacts() }, 'move');
  });
}

function pointerUp(pointerId) {
  const contacts = slots.up(pointerId);
  if (!contacts) return;
  cancelMove();
  outbox?.push({ k: 't', p: contacts });
}

touch.addEventListener('contextmenu', event => event.preventDefault());
if (window.PointerEvent) {
  touch.addEventListener('pointerdown', event => {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    if (!pointerDown(event.pointerId, event.clientX, event.clientY)) return;
    event.preventDefault();
    touch.setPointerCapture(event.pointerId);
  });
  touch.addEventListener('pointermove', event => pointerMove(event.pointerId, event.clientX, event.clientY));
  for (const type of ['pointerup', 'pointercancel', 'lostpointercapture']) touch.addEventListener(type, event => pointerUp(event.pointerId));
} else {
  const each = (event, handle) => {
    event.preventDefault();
    for (const contact of event.changedTouches) handle(contact.identifier, contact.clientX, contact.clientY);
  };
  touch.addEventListener('touchstart', event => each(event, pointerDown), { passive: false });
  touch.addEventListener('touchmove', event => each(event, pointerMove), { passive: false });
  for (const type of ['touchend', 'touchcancel']) touch.addEventListener(type, event => each(event, pointerUp));
}
window.addEventListener('blur', releaseTouches);
document.addEventListener('visibilitychange', () => {
  if (document.hidden) releaseTouches();
  else keepScreenOn();
});

// Viewport: the canvas backing store follows at once; the phone hears about it debounced and deduplicated.
function resizeCanvas() {
  if (stage.hidden) return;
  const rect = stage.getBoundingClientRect(), dpr = window.devicePixelRatio || 1;
  sink?.resize(Math.round(rect.width * dpr), Math.round(rect.height * dpr));
}

function scheduleViewport() {
  clearTimeout(viewportTimer);
  viewportTimer = setTimeout(() => {
    if (stage.hidden || !outbox) return;
    const rect = stage.getBoundingClientRect();
    const viewport = viewportFor(rect.width, rect.height, window.devicePixelRatio || 1);
    if (!viewport || `${viewport.w}x${viewport.h}` === lastViewport) return;
    lastViewport = `${viewport.w}x${viewport.h}`;
    outbox.push({ k: 'vp', ...viewport }, 'latest');
  }, 180);
}

const onResize = () => {
  resizeCanvas();
  scheduleViewport();
};
new ResizeObserver(onResize).observe(stage);
window.addEventListener('resize', onResize);

function keepScreenOn() {
  if (!active || wakeLock || document.hidden || !navigator.wakeLock) return;
  wakeLock = navigator.wakeLock.request('screen').then(lock => {
    lock.addEventListener('release', () => { wakeLock = null; });
    return lock;
  }, () => {
    wakeLock = null; // refused (no user activation yet, or a policy); the next decoded epoch asks again
  });
}

$('link-form').addEventListener('submit', event => {
  event.preventDefault();
  const code = $('code').value.trim(), host = normalizeHost($('host').value || pageHost || DEFAULT_HOST);
  if (!isCode(code)) {
    stop('invalidCode');
    $('code').focus();
    return;
  }
  if (!host) {
    $('advanced').open = true;
    stop('invalidHost');
    $('host').focus();
    return;
  }
  link = { host, code };
  $('host').value = host;
  saveLink();
  showLink();
  start();
});
$('disconnect').addEventListener('click', () => {
  sendBye();
  stop('stopped');
});
$('fit').addEventListener('click', () => {
  if (!outbox) return;
  fitRequestedAt = Date.now();
  outbox.push({ k: 'fit' });
  render();
});
$('stats-toggle').addEventListener('click', () => {
  statsVisible = !statsVisible;
  try { localStorage.setItem(STATS_KEY, statsVisible ? '1' : '0'); } catch (_) {}
  render();
});

const root = document.documentElement;
const requestFullscreen = root.requestFullscreen ?? root.webkitRequestFullscreen;
const fullscreenElement = () => document.fullscreenElement ?? document.webkitFullscreenElement;
if (requestFullscreen) {
  $('fullscreen').addEventListener('click', () => {
    const exit = document.exitFullscreen ?? document.webkitExitFullscreen;
    // Older Safari returns undefined instead of a promise.
    Promise.resolve(fullscreenElement() ? exit.call(document) : requestFullscreen.call(root, { navigationUI: 'hide' })).catch(() => {});
  });
  for (const type of ['fullscreenchange', 'webkitfullscreenchange']) {
    document.addEventListener(type, () => { $('fullscreen').textContent = t(fullscreenElement() ? 'exitFullscreen' : 'fullscreen'); });
  }
} else {
  $('fullscreen').hidden = true;
}
window.addEventListener('pagehide', () => {
  resumeOnShow = active;
  if (!active) return;
  sendBye();
  stop();
});
window.addEventListener('pageshow', event => {
  if (event.persisted && resumeOnShow) start();
});

if ('serviceWorker' in navigator) navigator.serviceWorker.register(new URL('sw.js', import.meta.url)).catch(() => {});

$('code').value = link.code ?? '';
$('host').value = link.host;
if (link.code) start();
else render();
pathReady.then(() => {
  if (videoPath.path !== 'none') {
    resizeCanvas(); // a start before the sink existed could not size the canvas, which then stays at 300 × 150
    return;
  }
  $('connect').disabled = true;
  stop('noVideo', videoPath.reason);
});
