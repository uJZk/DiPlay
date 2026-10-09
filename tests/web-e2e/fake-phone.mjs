// SPDX-License-Identifier: GPL-3.0-only
// A stand-in for the phone's BrowserLinkServer (protocol v1, §2–§4 of the browser-link contract), enough for the page:
// /hello, /video with records, /control with status, /bye, OPTIONS with CORS and Private Network Access headers,
// and optionally the page itself under /play/ (§7.2).
import { existsSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join } from 'node:path';
import { RECORD, FLAG, encodeRecord } from '../../site/play/link.js';

const PAGE_TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml' };
const PAGE_CSP = "default-src 'self'; media-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'none'";

const json = (res, status, body) => {
  res.writeHead(status, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
};

/**
 * Reads an IVF file into `{key, data}` frames. VP8 clears bit 0 of the first byte on a key frame; VP9 (profile 0,
 * no superframes) clears its frame_type bit, bit 2.
 */
export function parseIvf(buffer) {
  if (buffer.toString('latin1', 0, 4) !== 'DKIF') throw new Error('not an IVF file');
  const keyBit = { VP80: 0x01, VP90: 0x04 }[buffer.toString('latin1', 8, 12)];
  if (!keyBit) throw new Error('only VP8 and VP9 IVF files are supported');
  const frames = [];
  for (let offset = buffer.readUInt16LE(6); offset + 12 <= buffer.length;) {
    const size = buffer.readUInt32LE(offset);
    const data = new Uint8Array(buffer.subarray(offset + 12, offset + 12 + size));
    frames.push({ key: (data[0] & keyBit) === 0, data });
    offset += 12 + size;
  }
  return frames;
}

/**
 * Starts the fake phone. `frames` loop at `fps`; every stream starts at frame 0, which must be a key frame.
 * Events from /control land in `events` as `{s, q, event}`. With `pageDir` it also serves the page under /play/.
 */
export function startFakePhone({ code, frames, width, height, fps = 30, codec = 'vp8', host = '127.0.0.1', pageDir = null }) {
  const events = [], requests = [], preflights = [];
  let current = null, lastSeen = 0, video = null, fit = false, viewport = null, videoCount = 0, corrupt = false;
  const replaced = new Set(), lastQ = new Map();
  const config = new TextEncoder().encode(JSON.stringify({ codec, width, height, fps, format: 'annexb', marker: 'none' }));

  function closeVideo(reason) {
    if (!video) return;
    const { res, timer } = video;
    video = null;
    clearInterval(timer);
    if (reason) res.end(encodeRecord({ kind: RECORD.END, payload: new TextEncoder().encode(reason) }));
    else res.socket?.destroy();
  }

  /** Latest session wins; a replaced session gets 409 from then on. */
  function authorize(res, c, s) {
    if (c !== code) return json(res, 401, { error: 'code' }), false;
    if (!/^[A-Za-z0-9_-]{16,64}$/.test(s ?? '')) return json(res, 400, { error: 'bad-request' }), false;
    if (replaced.has(s)) return json(res, 409, { error: 'replaced' }), false;
    if (current !== s) {
      if (current && (video || Date.now() - lastSeen < 6000)) replaced.add(current);
      closeVideo('replaced');
      current = s;
    }
    lastSeen = Date.now();
    return true;
  }

  function streamVideo(res) {
    closeVideo('replaced');
    videoCount++;
    // Close-delimited body: no Content-Length and no Transfer-Encoding.
    res.useChunkedEncodingByDefault = false;
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', Connection: 'close' });
    res.socket.setNoDelay(true);
    res.write(encodeRecord({ kind: RECORD.CONFIG, epoch: 1, payload: config }));
    let sequence = 0, cursor = 0;
    const stream = {
      res,
      restart: () => { cursor = 0; },
      timer: setInterval(() => {
        const frame = corrupt ? { key: true, data: new Uint8Array(2000).fill(0x5a) } : frames[cursor++ % frames.length];
        corrupt = false;
        res.write(encodeRecord({
          kind: RECORD.FRAME, flags: frame.key ? FLAG.KEY : 0, epoch: 1,
          sequence, timestampUs: Math.round(sequence * 1e6 / fps), payload: frame.data,
        }));
        sequence++;
      }, 1000 / fps),
    };
    video = stream;
    res.on('close', () => {
      if (video !== stream) return;
      clearInterval(stream.timer);
      video = null;
    });
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      req.on('data', chunk => {
        size += chunk.length;
        if (size > 64 * 1024) reject(new Error('body too large'));
        else chunks.push(chunk);
      });
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
    });
  }

  function status(q) {
    return {
      q, state: 'streaming', codec, display: { w: width, h: height }, viewport, fit, touch: 'ok',
      phone: { fake: 1, videoStreams: videoCount },
    };
  }

  const server = createServer(async (req, res) => {
    const url = new URL(req.url, 'http://phone');
    requests.push({ method: req.method, path: url.pathname });
    if (req.headers.origin) {
      res.setHeader('Access-Control-Allow-Origin', req.headers.origin);
      res.setHeader('Vary', 'Origin');
    }
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    if (!/^\d{1,3}(\.\d{1,3}){3}(:\d+)?$/.test(req.headers.host ?? '')) return json(res, 421, { error: 'host' });
    if (req.method === 'OPTIONS') {
      preflights.push(Object.keys(req.headers));
      res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
      res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
      res.setHeader('Access-Control-Max-Age', '600');
      if (req.headers['access-control-request-private-network'] === 'true') res.setHeader('Access-Control-Allow-Private-Network', 'true');
      res.writeHead(204, { 'Content-Length': '0' });
      return res.end();
    }
    const route = `${req.method} ${url.pathname}`;
    if (pageDir && req.method === 'GET' && (url.pathname === '/' || url.pathname.startsWith('/play/'))) return servePage(res, url.pathname);
    if (route === 'GET /hello') return json(res, 200, { app: 'TiPlay', protocol: 1 });
    if (route === 'GET /video') {
      if (authorize(res, url.searchParams.get('c'), url.searchParams.get('s'))) streamVideo(res);
      return;
    }
    if (route === 'POST /control' || route === 'POST /bye') {
      let body;
      try {
        body = JSON.parse(await readBody(req));
      } catch (_) {
        return json(res, 400, { error: 'bad-request' });
      }
      if (!authorize(res, body.c, body.s)) return;
      if (route === 'POST /bye') {
        if (video) closeVideo('stopped');
        current = null;
        res.writeHead(204);
        return res.end();
      }
      if (body.q > (lastQ.get(body.s) ?? 0)) {
        lastQ.set(body.s, body.q);
        for (const event of body.e) {
          events.push({ s: body.s, q: body.q, event });
          if (event.k === 'vp') viewport = { w: event.w, h: event.h };
          if (event.k === 'kf') video?.restart();
        }
      }
      return json(res, 200, status(lastQ.get(body.s)));
    }
    if (['/hello', '/video', '/control', '/bye'].includes(url.pathname)) return json(res, 405, { error: 'method' });
    json(res, 404, { error: 'not-found' });
  });

  function servePage(res, path) {
    if (path === '/') return res.writeHead(302, { Location: '/play/' }).end();
    const name = path === '/play/' ? 'index.html' : path.slice('/play/'.length);
    if (name.includes('/') || !existsSync(join(pageDir, name))) return json(res, 404, { error: 'not-found' });
    res.writeHead(200, { 'Content-Type': PAGE_TYPES[extname(name)] ?? 'application/octet-stream', 'Cache-Control': 'no-cache', 'Content-Security-Policy': PAGE_CSP });
    res.end(readFileSync(join(pageDir, name)));
  }

  return new Promise(resolve => server.listen(0, host, () => resolve({
    port: server.address().port,
    events, requests, preflights,
    get videoCount() { return videoCount; },
    setFit(value) { fit = value; },
    /** Drops the current /video socket without an end record, as a vanished hotspot would. */
    dropVideo() { closeVideo(null); },
    /** Sends a key frame of garbage next, which the browser's decoder rejects. */
    corruptNextFrame() { corrupt = true; },
    close() {
      closeVideo('stopped');
      server.closeAllConnections();
      return new Promise(done => server.close(done));
    },
  })));
}
