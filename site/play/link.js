/*! SPDX-License-Identifier: GPL-3.0-only
 * Adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
 * common/src/main/assets/web/app.js at commit c1bd077 (mapPoint, control outbox, viewport report).
 * Modified for TiPlay, 2026-10.
 *
 * Pure helpers shared by the page, the decoder worker and the unit tests. No DOM access here.
 * Page code stays within syntax that Safari 15 and Firefox 115 ESR run (ES2020 modules). */

export const DEFAULT_HOST = '100.109.220.253:8080';
export const PROTOCOL = 1;
export const RECORD = Object.freeze({ CONFIG: 1, FRAME: 2, HEARTBEAT: 3, END: 4 });
export const FLAG = Object.freeze({ KEY: 1, DISCONTINUITY: 2, PARAMETER_SETS: 4 });
export const HEADER_BYTES = 24;
export const MAX_PAYLOAD = 8 * 1024 * 1024;

const CODE = /^[0-9]{6}$/;
const HOST = /^(?:(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})|(localhost))(?::([1-9]\d{0,4}))?$/i;
const utf8 = new TextDecoder();

export function isCode(value) {
  return typeof value === 'string' && CODE.test(value);
}

/**
 * Returns `a.b.c.d:port` or `localhost:port` (port 8080 when omitted), or null. The phone answers only IPv4 literals,
 * and `localhost` for the page it serves itself.
 */
export function normalizeHost(value) {
  const match = HOST.exec(String(value ?? '').trim());
  if (!match) return null;
  const port = match[6] === undefined ? 8080 : Number(match[6]);
  if (port > 65535) return null;
  if (match[5]) return `localhost:${port}`;
  const octets = match.slice(1, 5).map(Number);
  if (octets.some(octet => octet > 255)) return null;
  return `${octets.join('.')}:${port}`;
}

/** Reads `#h=<host:port>&c=<code>`; a missing or invalid value is null. */
export function parseLinkHash(hash) {
  const params = new URLSearchParams(String(hash ?? '').replace(/^#/, ''));
  const code = params.get('c');
  return { host: params.has('h') ? normalizeHost(params.get('h')) : null, code: isCode(code) ? code : null };
}

/** Reads the phone address from `?t=<host[:port]>`, the part of the link a bookmark keeps; null when missing or invalid. */
export function parseLinkSearch(search) {
  const params = new URLSearchParams(String(search ?? '').replace(/^\?/, ''));
  return { host: params.has('t') ? normalizeHost(params.get('t')) : null };
}

/**
 * The phone and code that the page's address names over the `saved` link, or null when it names neither. `?t=` (or the
 * older `#h=`) names the phone. A link with a code but no phone is a pairing link for DEFAULT_HOST, because links leave
 * `t` out for it; on the page the phone serves, it is for that phone. Without a code (a bookmark) the saved phone stays.
 */
export function linkFromAddress(search, hash, saved, pageHost = null) {
  const { host: bookmarked } = parseLinkSearch(search), linked = parseLinkHash(hash);
  if (!bookmarked && !linked.host && !linked.code) return null;
  const host = bookmarked ?? linked.host ?? (linked.code ? pageHost ?? DEFAULT_HOST : saved.host);
  return { host, code: linked.code ?? saved.code };
}

/** The `t` value for a host: the default port 8080 is left out. */
export function hostParam(host) {
  return String(host).replace(/:8080$/, '');
}

/**
 * The address-bar path for the current phone address: `?t=` names the phone so a bookmark reopens it, other query
 * parameters stay, and the fragment (which may hold the pairing code) is dropped. `t` is left out for DEFAULT_HOST, so
 * the usual link is the page's own address, and on a page the phone serves itself, which talks to its own origin.
 */
export function linkLocation(pathname, search, host, pageHost = null) {
  const params = String(search ?? '').replace(/^\?/, '').split('&').filter(part => part && !/^t(=|$)/.test(part));
  if (host && host !== DEFAULT_HOST && host !== pageHost) params.unshift(`t=${hostParam(host)}`);
  return `${pathname}${params.length ? `?${params.join('&')}` : ''}`;
}

/**
 * A link to the page: `?t=` carries the phone address unless it is DEFAULT_HOST, `#c=` the pairing code (a fragment
 * never reaches the page host).
 */
export function buildLink(base, { host, code }) {
  const [path, query] = String(base).split('#')[0].split('?');
  return `${linkLocation(path, query, host)}${code ? `#c=${code}` : ''}`;
}

/** The `targetAddressSpace` fetch option for a host. Chrome fails a request whose declared space does not match. */
export function addressSpaceFor(host) {
  const [a, b] = String(host).split(/[.:]/).map(Number);
  if (a === 127 || /^localhost(:|$)/i.test(host)) return 'loopback';
  if (a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) ||
      (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254)) return 'local';
  return undefined;
}

/** A random session id of 22 URL-safe characters. */
export function newSessionId(random = bytes => crypto.getRandomValues(bytes)) {
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  return Array.from(random(new Uint8Array(22)), byte => alphabet[byte & 63]).join('');
}

export const backoffDelay = attempt => Math.min(8000, 500 * 2 ** attempt);

/** An AbortSignal that aborts after `ms`; `AbortSignal.timeout` is missing before Safari 16. */
export function timeoutSignal(ms) {
  const controller = new AbortController();
  setTimeout(() => controller.abort(), ms);
  return controller.signal;
}

/**
 * The video path for this browser (contract §7.2). The first rung is the Tesla path; each later one replaces only
 * what is missing. `features`: secureContext, videoDecoder, workerVideoDecoder, offscreenCanvas, transferableStreams,
 * mediaSource. `decode` and `draw` say where that work happens; `feed` is how the worker gets the response body.
 */
export function choosePath(features) {
  if (features.secureContext && features.videoDecoder) {
    if (!features.workerVideoDecoder) return { path: 'main', decode: 'main', draw: 'main', feed: null, reason: 'no VideoDecoder in workers' };
    const feed = features.transferableStreams ? 'stream' : 'chunks';
    const reasons = [];
    if (!features.transferableStreams) reasons.push('streams are not transferable');
    if (!features.offscreenCanvas) reasons.push('no OffscreenCanvas');
    const draw = features.offscreenCanvas ? 'worker' : 'main';
    const path = draw === 'main' ? 'worker-frames' : feed === 'chunks' ? 'worker-chunks' : 'worker';
    return { path, decode: 'worker', draw, feed, reason: reasons.join(', ') || null };
  }
  const reason = features.secureContext ? 'no WebCodecs' : 'not a secure context';
  if (features.mediaSource) return { path: 'mse', decode: 'mse', draw: 'video', feed: null, reason };
  return { path: 'none', decode: null, draw: null, feed: null, reason: `${reason}, no MediaSource` };
}

export class RecordError extends Error {}

/**
 * Reassembles §3 video records from arbitrary chunks. A payload that lies inside one chunk is returned as a view of
 * that chunk; otherwise it is copied once. After a RecordError every later push throws the same error.
 */
export class RecordParser {
  constructor() {
    this.header = new Uint8Array(HEADER_BYTES);
    this.view = new DataView(this.header.buffer);
    this.headerFill = 0;
    this.record = null;
    this.payloadFill = 0;
    this.failure = null;
  }

  push(chunk) {
    if (this.failure) throw this.failure;
    const records = [];
    let offset = 0;
    while (offset < chunk.length) {
      if (!this.record) {
        const take = Math.min(HEADER_BYTES - this.headerFill, chunk.length - offset);
        this.header.set(chunk.subarray(offset, offset + take), this.headerFill);
        this.headerFill += take;
        offset += take;
        if (this.headerFill < HEADER_BYTES) break;
        this.headerFill = 0;
        const record = this.readHeader();
        if (chunk.length - offset >= record.payloadLength) {
          record.payload = chunk.subarray(offset, offset + record.payloadLength);
          offset += record.payloadLength;
          records.push(record);
          continue;
        }
        record.payload = new Uint8Array(record.payloadLength);
        this.record = record;
        this.payloadFill = 0;
      }
      const record = this.record;
      const take = Math.min(record.payloadLength - this.payloadFill, chunk.length - offset);
      record.payload.set(chunk.subarray(offset, offset + take), this.payloadFill);
      this.payloadFill += take;
      offset += take;
      if (this.payloadFill === record.payloadLength) {
        this.record = null;
        records.push(record);
      }
    }
    return records;
  }

  readHeader() {
    const view = this.view;
    const payloadLength = view.getUint32(0, true);
    const version = view.getUint8(7);
    if (version !== PROTOCOL) this.fail(`unknown record version ${version}`);
    if (payloadLength > MAX_PAYLOAD) this.fail(`record payload of ${payloadLength} bytes`);
    return {
      payloadLength,
      kind: view.getUint8(4),
      flags: view.getUint8(5),
      codec: view.getUint8(6),
      version,
      sequence: view.getUint32(8, true),
      epoch: view.getUint32(12, true),
      timestampUs: Number(view.getBigInt64(16, true)),
      payload: null,
    };
  }

  fail(message) {
    this.failure = new RecordError(message);
    throw this.failure;
  }
}

/** Encodes one record; used by tests and the end-to-end fake phone. */
export function encodeRecord({ kind, flags = 0, codec = 0, sequence = 0, epoch = 0, timestampUs = 0, payload = new Uint8Array(0), version = PROTOCOL }) {
  const bytes = new Uint8Array(HEADER_BYTES + payload.length);
  const view = new DataView(bytes.buffer);
  view.setUint32(0, payload.length, true);
  view.setUint8(4, kind);
  view.setUint8(5, flags);
  view.setUint8(6, codec);
  view.setUint8(7, version);
  view.setUint32(8, sequence, true);
  view.setUint32(12, epoch, true);
  view.setBigInt64(16, BigInt(timestampUs), true);
  bytes.set(payload, HEADER_BYTES);
  return bytes;
}

/**
 * Reads §3 records from one response body at a time. `onRecord(record)` may return a promise; the next record waits
 * for it. `onEnd(id, reason)` says why a stream ended: the end record's text, `closed`, `network`, `bad-record`,
 * `error` (onRecord threw) or `timeout` (no record for `timeoutMs`; call `watchdog()` about once a second).
 * A stream that `read()` replaced or `stop()` ended reports nothing.
 */
export function createRecordStream({ onRecord, onAlive = () => {}, onEnd = () => {}, timeoutMs = 4000, now = () => performance.now() }) {
  const totals = { bytes: 0, records: 0, gaps: 0, discontinuities: 0 };
  let current = null;

  function stop() {
    if (!current) return;
    current.reader.cancel().catch(() => {});
    current = null;
  }

  async function read(id, stream) {
    stop();
    const reader = stream.getReader();
    const parser = new RecordParser();
    const mine = current = { id, reader, lastRecordAt: now(), alive: false, lastSequence: null };
    let reason = 'closed';
    try {
      reading: for (;;) {
        let chunk;
        try {
          chunk = await reader.read();
        } catch (_) {
          reason = 'network';
          break;
        }
        if (current !== mine) return;
        if (chunk.done) break;
        totals.bytes += chunk.value.byteLength;
        for (const record of parser.push(chunk.value)) {
          totals.records++;
          mine.lastRecordAt = now();
          if (!mine.alive) {
            mine.alive = true;
            onAlive(id);
          }
          if (record.kind === RECORD.END) {
            reason = utf8.decode(record.payload) || 'end';
            break reading;
          }
          if (record.kind === RECORD.FRAME) {
            if (mine.lastSequence !== null && record.sequence > mine.lastSequence + 1) totals.gaps += record.sequence - mine.lastSequence - 1;
            mine.lastSequence = record.sequence;
            if (record.flags & FLAG.DISCONTINUITY) totals.discontinuities++;
          }
          const pending = onRecord(record);
          if (pending) await pending;
          if (current !== mine) return;
        }
      }
    } catch (error) {
      if (current !== mine) return;
      reason = error instanceof RecordError ? 'bad-record' : 'error';
    }
    if (current !== mine) return;
    current = null;
    reader.cancel().catch(() => {});
    onEnd(id, reason);
  }

  return {
    read,
    stop,
    watchdog() {
      if (!current || now() - current.lastRecordAt <= timeoutMs) return;
      const { id } = current;
      stop();
      onEnd(id, 'timeout');
    },
    stats: () => ({ ...totals, lastRecordAgeMs: current ? Math.round(now() - current.lastRecordAt) : null }),
  };
}

// Coordinates are normalized in the visible video rectangle, excluding letterboxing.
export function mapPoint(clientX, clientY, rect, width, height, clamp = false) {
  if (width <= 0 || height <= 0 || rect.width <= 0 || rect.height <= 0) return null;
  const scale = Math.min(rect.width / width, rect.height / height);
  const w = width * scale, h = height * scale;
  const x = (clientX - rect.left - (rect.width - w) / 2) / w;
  const y = (clientY - rect.top - (rect.height - h) / 2) / h;
  if (!clamp && (x < 0 || x > 1 || y < 0 || y > 1)) return null;
  return { x: Math.max(0, Math.min(1, x)), y: Math.max(0, Math.min(1, y)) };
}

/** The centred rectangle (whole pixels) that shows a width × height picture inside an outer box. Inverse of mapPoint. */
export function letterbox(outerWidth, outerHeight, width, height) {
  if (width <= 0 || height <= 0) return { x: 0, y: 0, width: outerWidth, height: outerHeight };
  const scale = Math.min(outerWidth / width, outerHeight / height);
  const w = width * scale, h = height * scale;
  return { x: Math.round((outerWidth - w) / 2), y: Math.round((outerHeight - h) / 2), width: Math.round(w), height: Math.round(h) };
}

const round4 = value => Math.round(value * 10000) / 10000;

/**
 * Pointer → touch slot assignment. A new pointer takes the free lower slot and keeps it until it lifts.
 * Contacts are `[slot, x, y, down]`, as in the `t` event.
 */
export function createTouchSlots(max = 2) {
  const pointers = new Map();
  const contact = (p, down) => [p.slot, round4(p.x), round4(p.y), down];
  const bySlot = (a, b) => a[0] - b[0];
  const contacts = () => [...pointers.values()].map(p => contact(p, 1)).sort(bySlot);
  return {
    get size() { return pointers.size; },
    has: pointerId => pointers.has(pointerId),
    down(pointerId, { x, y }) {
      if (pointers.size >= max || pointers.has(pointerId)) return false;
      const used = new Set([...pointers.values()].map(p => p.slot));
      let slot = 0;
      while (used.has(slot)) slot++;
      pointers.set(pointerId, { slot, x, y });
      return true;
    },
    move(pointerId, { x, y }) {
      const p = pointers.get(pointerId);
      if (!p) return false;
      p.x = x;
      p.y = y;
      return true;
    },
    /** Removes the pointer; returns the held contacts plus the lifted one with down 0, or null. */
    up(pointerId) {
      const p = pointers.get(pointerId);
      if (!p) return null;
      pointers.delete(pointerId);
      return [...contacts(), contact(p, 0)].sort(bySlot);
    },
    contacts,
    /** Lifts every pointer; returns them with down 0 (empty when nothing was held). */
    releaseAll() {
      const lifted = [...pointers.values()].map(p => contact(p, 0)).sort(bySlot);
      pointers.clear();
      return lifted;
    },
  };
}

/**
 * The `/control` sender: exactly one POST in flight, events in order. A `move` replaces a move at the queue tail,
 * a `latest` event replaces a queued event of the same `k`, and every other event (touch edges included) is kept.
 * More than `maxEvents` queued events clear the queue and report an overflow; the phone lifts held fingers after
 * 1500 ms without touch updates. A failed POST is not replayed as such. The next heartbeat repeats its `latest`
 * events that nothing newer replaced, and when it carried touches, the current contacts (an empty list lifts a
 * finger whose up edge was lost). `heartbeat()` should run every 400 ms; it re-sends held contacts.
 *
 * `send(q, events)` performs the POST and resolves with the status JSON; it must time out on its own.
 */
export function createControlOutbox({
  send, heldContacts = () => [], onStatus = () => {}, onError = () => {},
  now = () => Date.now(), maxEvents = 64,
} = {}) {
  const queue = [], retry = new Map();
  const rtt = new Samples(64);
  let seq = 0, inFlight = false, heartbeatDue = false, closed = false, touchLost = false;
  let posts = 0, failures = 0, overflows = 0;

  function flush() {
    if (closed || inFlight || (!queue.length && !heartbeatDue)) return;
    const batch = queue.splice(0), events = batch.map(entry => entry.event);
    const q = ++seq, started = now();
    heartbeatDue = false;
    inFlight = true;
    posts++;
    Promise.resolve().then(() => send(q, events)).then(status => {
      if (closed) return;
      inFlight = false;
      rtt.add(now() - started);
      onStatus(status);
      flush();
    }, error => {
      if (closed) return;
      inFlight = false;
      failures++;
      // The phone may not have seen these; an event queued meanwhile already replaces one of the same `k`.
      for (const entry of batch) {
        if (queue.some(queued => queued.event.k === entry.event.k)) continue;
        if (entry.kind === 'latest') retry.set(entry.event.k, entry);
        else if (entry.event.k === 't') touchLost = true;
      }
      onError(error);
      flush();
    });
  }

  function push(event, kind = 'edge') {
    if (closed) return false;
    // A newer event of the same `k` makes the repeat of a failed one obsolete.
    retry.delete(event.k);
    if (event.k === 't') touchLost = false;
    const tail = queue[queue.length - 1];
    const same = kind === 'latest' ? queue.find(entry => entry.event.k === event.k) : null;
    if (kind === 'move' && tail?.kind === 'move') {
      tail.event = event;
    } else if (same) {
      same.event = event;
    } else if (queue.length < maxEvents) {
      queue.push({ kind, event });
    } else {
      queue.length = 0;
      overflows++;
      onError(new Error('control queue overflow'));
      return false;
    }
    flush();
    return true;
  }

  return {
    push,
    heartbeat() {
      queue.unshift(...retry.values());
      retry.clear();
      const held = heldContacts();
      heartbeatDue = true;
      if ((!held.length && !touchLost) || !push({ k: 't', p: held }, 'move')) flush();
    },
    close() {
      closed = true;
      queue.length = 0;
    },
    stats: () => ({
      seq, inFlight, pending: queue.length, posts, failures, overflows,
      rttMsP50: rtt.percentile(50), rttMsP95: rtt.percentile(95),
    }),
  };
}

/** Device pixels of the video area (CSS × DPR, rounded to even), or null when the phone would reject them. */
export function viewportFor(cssWidth, cssHeight, devicePixelRatio) {
  const dpr = Number.isFinite(devicePixelRatio) && devicePixelRatio > 0 ? devicePixelRatio : 1;
  const w = Math.round(cssWidth * dpr / 2) * 2, h = Math.round(cssHeight * dpr / 2) * 2;
  if (!(w >= 320 && h >= 320 && w <= 4096 && h <= 4096)) return null;
  if (w / h < 0.5 || w / h > 4) return null;
  return { w, h, cw: Math.round(cssWidth), ch: Math.round(cssHeight), dpr: Math.round(dpr * 100) / 100 };
}

/** Representative codec strings for the diagnostic `codecs=` list of `/video`. */
export const PROBE_CODECS = Object.freeze({
  avc1: 'avc1.640028', hvc1: 'hvc1.1.6.L120.90', vp8: 'vp8', vp09: 'vp09.00.40.08', av01: 'av01.0.08M.08',
});

/** Names from `codecs` whose string `isConfigSupported` accepts at 1280×720. */
export async function supportedCodecs(isConfigSupported, codecs = PROBE_CODECS) {
  const results = await Promise.all(Object.entries(codecs).map(async ([name, codec]) => {
    try {
      return (await isConfigSupported({ codec, codedWidth: 1280, codedHeight: 720 })).supported ? name : null;
    } catch (_) {
      return null;
    }
  }));
  return results.filter(Boolean);
}

/** The decoder config for a §3 config record: hardware preferred, any decoder otherwise; null when unsupported. */
export async function chooseDecoderConfig(isConfigSupported, { codec, width, height }) {
  for (const hardwareAcceleration of ['prefer-hardware', 'no-preference']) {
    const config = { codec, codedWidth: width, codedHeight: height, optimizeForLatency: true, hardwareAcceleration };
    try {
      if ((await isConfigSupported(config)).supported) return config;
    } catch (_) {
      // An invalid codec string throws; the next preference cannot fix that, but it costs nothing to ask.
    }
  }
  return null;
}

/** A bounded window of recent samples. */
export class Samples {
  constructor(capacity = 240) {
    this.values = [];
    this.capacity = capacity;
  }

  add(value) {
    this.values.push(value);
    if (this.values.length > this.capacity) this.values.shift();
  }

  /** Nearest-rank percentile rounded to 0.01, or null without samples. */
  percentile(p) {
    if (!this.values.length) return null;
    const sorted = [...this.values].sort((a, b) => a - b);
    const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(p / 100 * sorted.length) - 1));
    return Math.round(sorted[index] * 100) / 100;
  }
}

/** Flattens nested stats into the `st` event shape: numbers and short strings only, at most `limit` JSON bytes. */
export function flatStats(stats, limit = 2048) {
  const flat = {};
  let size = 2;
  const add = (key, value) => {
    const entry = JSON.stringify(key).length + JSON.stringify(value).length + 2;
    if (size + entry > limit) return;
    flat[key] = value;
    size += entry;
  };
  const visit = (prefix, value) => {
    if (typeof value === 'number' && Number.isFinite(value)) add(prefix, Math.round(value * 100) / 100);
    else if (typeof value === 'boolean') add(prefix, value ? 1 : 0);
    else if (typeof value === 'string') add(prefix, value.slice(0, 40));
    else if (value && typeof value === 'object' && !Array.isArray(value)) {
      for (const [key, inner] of Object.entries(value)) visit(prefix ? `${prefix}.${key}` : key, inner);
    }
  };
  visit('', stats);
  return flat;
}
