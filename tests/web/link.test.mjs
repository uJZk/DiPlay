// SPDX-License-Identifier: GPL-3.0-only
// mapPoint cases adapted from WheelPlay (https://github.com/fython/wheelplay), GPL-3.0-only,
// test/client.test.js and test/outbox.test.js at commit c1bd077. Modified for TiPlay, 2026-10.
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_HOST, FLAG, HEADER_BYTES, MAX_PAYLOAD, RECORD, RecordError, RecordParser, Samples, addressSpaceFor,
  backoffDelay, buildLink, chooseDecoderConfig, choosePath, createControlOutbox, createRecordStream, createTouchSlots,
  encodeRecord, flatStats, hostParam, letterbox, linkLocation, mapPoint, newSessionId, normalizeHost, parseLinkHash,
  parseLinkSearch, supportedCodecs, viewportFor,
} from '../../site/play/link.js';

const utf8 = text => new TextEncoder().encode(text);
const concat = parts => {
  const bytes = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    bytes.set(part, offset);
    offset += part.length;
  }
  return bytes;
};
const plain = record => ({ ...record, payload: Array.from(record.payload) });

test('link hash carries an IPv4 host with port and a six-digit code', () => {
  assert.deepEqual(parseLinkHash('#h=100.109.220.253:8080&c=123456'), { host: '100.109.220.253:8080', code: '123456' });
  assert.deepEqual(parseLinkHash('#h=100.109.220.253%3A8080&c=000042'), { host: '100.109.220.253:8080', code: '000042' });
  assert.deepEqual(parseLinkHash('#c=123456'), { host: null, code: '123456' });
  assert.deepEqual(parseLinkHash('#h=10.0.0.1'), { host: '10.0.0.1:8080', code: null });
  assert.deepEqual(parseLinkHash(''), { host: null, code: null });
  for (const code of ['12345', '1234567', '12x456', ' 123456']) assert.equal(parseLinkHash(`#c=${code}`).code, null, code);
  for (const host of ['phone.local:8080', '256.1.1.1:8080', '1.2.3.4:0', '1.2.3.4:65536', '01.2.3.4:80', '[::1]:8080']) {
    assert.equal(parseLinkHash(`#h=${host}`).host, null, host);
  }
});

test('built links put the phone in ?t= for bookmarks and the code in the fragment', () => {
  const link = buildLink('https://example.test/play/#old', { host: DEFAULT_HOST, code: '987654' });
  assert.equal(link, 'https://example.test/play/?t=100.109.220.253#c=987654');
  assert.deepEqual(parseLinkSearch(new URL(link).search), { host: DEFAULT_HOST });
  assert.deepEqual(parseLinkHash(new URL(link).hash), { host: null, code: '987654' });
  assert.equal(buildLink('https://example.test/play/?t=1.2.3.4&lang=zh', { host: '100.64.0.1:9000', code: null }),
    'https://example.test/play/?t=100.64.0.1:9000&lang=zh', 'an older t is replaced and other parameters stay');
  assert.equal(normalizeHost(' 192.168.1.20:9000 '), '192.168.1.20:9000');
  assert.equal(normalizeHost('LOCALHOST'), 'localhost:8080', 'the page the phone serves may be opened on the phone itself');
});

test('?t= names the phone with an optional port that defaults to 8080', () => {
  assert.deepEqual(parseLinkSearch('?t=100.64.0.1'), { host: '100.64.0.1:8080' });
  assert.deepEqual(parseLinkSearch('?t=100.64.0.1:8080'), { host: '100.64.0.1:8080' });
  assert.deepEqual(parseLinkSearch('t=100.64.0.1:9000'), { host: '100.64.0.1:9000' });
  assert.deepEqual(parseLinkSearch('?t=100.64.0.1%3A9000'), { host: '100.64.0.1:9000' });
  assert.deepEqual(parseLinkSearch('?lang=zh'), { host: null });
  for (const value of ['', 'phone.local', '256.1.1.1', '1.2.3.4:0', '[::1]']) {
    assert.deepEqual(parseLinkSearch(`?t=${value}`), { host: null }, value);
  }
  assert.equal(hostParam('100.64.0.1:8080'), '100.64.0.1');
  assert.equal(hostParam('100.64.0.1:9000'), '100.64.0.1:9000');
});

test('the address bar keeps ?t= for bookmarks and drops the fragment with the code', () => {
  assert.equal(linkLocation('/play/', '', DEFAULT_HOST), '/play/?t=100.109.220.253');
  assert.equal(linkLocation('/play/', '?t=1.2.3.4&lang=zh', '100.64.0.1:9000'), '/play/?t=100.64.0.1:9000&lang=zh');
  assert.equal(linkLocation('/play/', '?t', '100.64.0.1:8080'), '/play/?t=100.64.0.1', 'a bare t is replaced');
  assert.equal(linkLocation('/play/', '?tab=1', '100.64.0.1:8080'), '/play/?t=100.64.0.1&tab=1', 'only t itself is replaced');
  assert.equal(linkLocation('/play/', '?t=1.2.3.4', '10.0.0.5:8080', '10.0.0.5:8080'), '/play/',
    'the page the phone serves talks to its own origin and needs no t');
});

test('the declared address space matches what Chrome resolves for the host', () => {
  assert.equal(addressSpaceFor('127.0.0.1:8080'), 'loopback');
  assert.equal(addressSpaceFor('localhost:8080'), 'loopback');
  for (const host of ['100.109.220.253:8080', '10.1.2.3:80', '172.16.0.1:80', '192.168.0.2:80', '169.254.1.1:80']) {
    assert.equal(addressSpaceFor(host), 'local', host);
  }
  for (const host of ['100.128.0.1:80', '172.32.0.1:80', '8.8.8.8:80']) assert.equal(addressSpaceFor(host), undefined, host);
});

test('session ids are 22 URL-safe characters', () => {
  const id = newSessionId(bytes => bytes.fill(255));
  assert.equal(id, '_'.repeat(22));
  assert.match(newSessionId(), /^[A-Za-z0-9_-]{22}$/);
});

test('reconnect backoff doubles from 500 ms up to 8 s', () => {
  assert.deepEqual([0, 1, 2, 3, 4, 5, 10].map(backoffDelay), [500, 1000, 2000, 4000, 8000, 8000, 8000]);
});

function sampleStream() {
  const records = [
    { kind: RECORD.CONFIG, codec: 1, epoch: 3, payload: utf8('{"codec":"avc1.640028","width":1182,"height":920}') },
    { kind: RECORD.FRAME, flags: FLAG.KEY | FLAG.PARAMETER_SETS, codec: 1, sequence: 0, epoch: 3, timestampUs: 0, payload: Uint8Array.of(0, 0, 0, 1, 0x67, 9, 8) },
    { kind: RECORD.FRAME, codec: 1, sequence: 2, epoch: 3, timestampUs: 33333, payload: Uint8Array.of(0, 0, 0, 1, 0x41) },
    { kind: RECORD.HEARTBEAT },
    { kind: RECORD.FRAME, flags: FLAG.KEY | FLAG.DISCONTINUITY, codec: 2, sequence: 4294967295, epoch: 4, timestampUs: 2 ** 40, payload: new Uint8Array(300).fill(7) },
    { kind: RECORD.END, payload: utf8('stopped') },
  ];
  const expected = records.map(r => ({
    payloadLength: r.payload?.length ?? 0, kind: r.kind, flags: r.flags ?? 0, codec: r.codec ?? 0, version: 1,
    sequence: r.sequence ?? 0, epoch: r.epoch ?? 0, timestampUs: r.timestampUs ?? 0, payload: Array.from(r.payload ?? []),
  }));
  return { bytes: concat(records.map(encodeRecord)), expected };
}

test('records are reassembled across a split at every byte boundary', () => {
  const { bytes, expected } = sampleStream();
  for (let split = 0; split <= bytes.length; split++) {
    const parser = new RecordParser();
    const records = [...parser.push(bytes.subarray(0, split)), ...parser.push(bytes.subarray(split))];
    assert.deepEqual(records.map(plain), expected, `split at ${split}`);
  }
});

test('records survive one-byte chunks and two splits', () => {
  const { bytes, expected } = sampleStream();
  const parser = new RecordParser();
  const records = [];
  for (let i = 0; i < bytes.length; i++) records.push(...parser.push(bytes.subarray(i, i + 1)));
  assert.deepEqual(records.map(plain), expected);
  for (let a = 0; a < bytes.length; a += 7) {
    for (let b = a; b <= bytes.length; b += 5) {
      const twice = new RecordParser();
      const parts = [bytes.subarray(0, a), bytes.subarray(a, b), bytes.subarray(b)].flatMap(part => twice.push(part));
      assert.deepEqual(parts.map(plain), expected, `splits at ${a} and ${b}`);
    }
  }
});

test('a payload inside one chunk is a view, a split payload a copy', () => {
  const frame = encodeRecord({ kind: RECORD.FRAME, payload: Uint8Array.of(1, 2, 3) });
  const [whole] = new RecordParser().push(frame);
  assert.equal(whole.payload.buffer, frame.buffer);
  const parser = new RecordParser();
  parser.push(frame.subarray(0, HEADER_BYTES + 1));
  const [split] = parser.push(frame.subarray(HEADER_BYTES + 1));
  assert.notEqual(split.payload.buffer, frame.buffer);
  assert.deepEqual(Array.from(split.payload), [1, 2, 3]);
});

test('an oversized payload or an unknown version is rejected for good', () => {
  const oversized = encodeRecord({ kind: RECORD.FRAME });
  new DataView(oversized.buffer).setUint32(0, MAX_PAYLOAD + 1, true);
  const parser = new RecordParser();
  assert.throws(() => parser.push(oversized), RecordError);
  assert.throws(() => parser.push(encodeRecord({ kind: RECORD.HEARTBEAT })), RecordError);

  const largest = encodeRecord({ kind: RECORD.FRAME });
  new DataView(largest.buffer).setUint32(0, MAX_PAYLOAD, true);
  assert.deepEqual(new RecordParser().push(largest), []);

  const future = new RecordParser();
  const header = encodeRecord({ kind: RECORD.HEARTBEAT, version: 2 });
  assert.deepEqual(future.push(header.subarray(0, 8)), []);
  assert.throws(() => future.push(header.subarray(8)), /version 2/);
});

function streamOf(chunks, { fail = false } = {}) {
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk);
      if (fail) controller.error(new TypeError('network'));
      else controller.close();
    },
  });
}

function recordStreamFixture(options = {}) {
  let time = 0;
  const seen = [], ends = [], alive = [];
  const stream = createRecordStream({
    onRecord: record => { seen.push(record.kind); },
    onAlive: id => alive.push(id),
    onEnd: (id, reason) => ends.push([id, reason]),
    now: () => time,
    ...options,
  });
  return { stream, seen, ends, alive, advance: ms => { time += ms; } };
}

test('a record stream reports the end record reason, gaps and discontinuities', async () => {
  const f = recordStreamFixture();
  const bytes = concat([
    encodeRecord({ kind: RECORD.CONFIG, payload: utf8('{}') }),
    encodeRecord({ kind: RECORD.FRAME, flags: FLAG.KEY, sequence: 1 }),
    encodeRecord({ kind: RECORD.FRAME, sequence: 4 }),
    encodeRecord({ kind: RECORD.FRAME, flags: FLAG.KEY | FLAG.DISCONTINUITY, sequence: 9 }),
    encodeRecord({ kind: RECORD.END, payload: utf8('replaced') }),
    encodeRecord({ kind: RECORD.FRAME, sequence: 10 }),
  ]);
  await f.stream.read(7, streamOf([bytes.subarray(0, 30), bytes.subarray(30)]));
  assert.deepEqual(f.seen, [RECORD.CONFIG, RECORD.FRAME, RECORD.FRAME, RECORD.FRAME]);
  assert.deepEqual(f.ends, [[7, 'replaced']]);
  assert.deepEqual(f.alive, [7]);
  const stats = f.stream.stats();
  assert.equal(stats.gaps, 6);
  assert.equal(stats.discontinuities, 1);
  assert.equal(stats.records, 5);
});

test('a record stream ends as closed, network, bad-record or error', async () => {
  const f = recordStreamFixture();
  await f.stream.read(1, streamOf([encodeRecord({ kind: RECORD.HEARTBEAT })]));
  await f.stream.read(2, streamOf([], { fail: true }));
  await f.stream.read(3, streamOf([encodeRecord({ kind: RECORD.HEARTBEAT, version: 9 })]));
  const failing = recordStreamFixture({ onRecord: () => Promise.reject(new Error('bug')) });
  await failing.stream.read(4, streamOf([encodeRecord({ kind: RECORD.FRAME })]));
  assert.deepEqual([...f.ends, ...failing.ends], [[1, 'closed'], [2, 'network'], [3, 'bad-record'], [4, 'error']]);
});

test('a silent stream times out, and a replaced or stopped one reports nothing', async () => {
  const f = recordStreamFixture();
  let first, second;
  const silent = new ReadableStream({ start(controller) { first = controller; } });
  const reading = f.stream.read(1, silent);
  f.advance(4000);
  f.stream.watchdog();
  assert.deepEqual(f.ends, []);
  f.advance(1);
  f.stream.watchdog();
  assert.deepEqual(f.ends, [[1, 'timeout']]);
  await reading;
  const replaced = f.stream.read(2, new ReadableStream({ start(controller) { second = controller; } }));
  const next = f.stream.read(3, streamOf([]));
  await Promise.all([replaced, next]);
  f.stream.stop();
  assert.deepEqual(f.ends, [[1, 'timeout'], [3, 'closed']]);
  assert.ok(first && second);
});

test('the video path takes the Tesla rung when everything is there and replaces only what is missing', () => {
  const all = { secureContext: true, videoDecoder: true, workerVideoDecoder: true, offscreenCanvas: true, transferableStreams: true, mediaSource: true };
  assert.deepEqual(choosePath(all), { path: 'worker', decode: 'worker', draw: 'worker', feed: 'stream', reason: null });
  assert.deepEqual(choosePath({ ...all, transferableStreams: false }),
    { path: 'worker-chunks', decode: 'worker', draw: 'worker', feed: 'chunks', reason: 'streams are not transferable' });
  assert.deepEqual(choosePath({ ...all, offscreenCanvas: false }),
    { path: 'worker-frames', decode: 'worker', draw: 'main', feed: 'stream', reason: 'no OffscreenCanvas' });
  assert.equal(choosePath({ ...all, offscreenCanvas: false, transferableStreams: false }).reason, 'streams are not transferable, no OffscreenCanvas');
  assert.deepEqual(choosePath({ ...all, workerVideoDecoder: false }),
    { path: 'main', decode: 'main', draw: 'main', feed: null, reason: 'no VideoDecoder in workers' });
  assert.deepEqual(choosePath({ ...all, secureContext: false }), { path: 'mse', decode: 'mse', draw: 'video', feed: null, reason: 'not a secure context' });
  assert.equal(choosePath({ ...all, videoDecoder: false }).reason, 'no WebCodecs');
  assert.deepEqual(choosePath({ secureContext: false, mediaSource: false }),
    { path: 'none', decode: null, draw: null, feed: null, reason: 'not a secure context, no MediaSource' });
});

test('landscape pillarbox is excluded and video center maps correctly', () => {
  const rect = { left: 10, top: 80, width: 1600, height: 600 };
  assert.equal(mapPoint(20, 200, rect, 1280, 720), null);
  assert.deepEqual(mapPoint(810, 380, rect, 1280, 720), { x: 0.5, y: 0.5 });
});

test('portrait letterbox is excluded', () => {
  const rect = { left: 0, top: 0, width: 600, height: 900 };
  assert.equal(mapPoint(300, 20, rect, 1280, 720), null);
  assert.deepEqual(mapPoint(300, 450, rect, 1280, 720), { x: 0.5, y: 0.5 });
});

test('a captured drag clamps to frame edges', () => {
  const rect = { left: 0, top: 0, width: 1280, height: 720 };
  assert.deepEqual(mapPoint(-100, 900, rect, 1280, 720, true), { x: 0, y: 1 });
  assert.deepEqual(mapPoint(1280, 720, rect, 1280, 720), { x: 1, y: 1 });
});

test('zero sized frame cannot send a touch', () => {
  assert.equal(mapPoint(0, 0, { left: 0, top: 0, width: 0, height: 0 }, 1280, 720), null);
});

test('the drawn letterbox is the box mapPoint normalizes against', () => {
  assert.deepEqual(letterbox(1600, 600, 1280, 720), { x: 267, y: 0, width: 1067, height: 600 });
  assert.deepEqual(letterbox(600, 900, 1280, 720), { x: 0, y: 281, width: 600, height: 338 });
  const box = letterbox(1182, 1000, 1182, 920);
  const rect = { left: 0, top: 0, width: 1182, height: 1000 };
  assert.deepEqual(mapPoint(box.x, box.y, rect, 1182, 920), { x: 0, y: 0 });
  assert.deepEqual(mapPoint(box.x + box.width, box.y + box.height, rect, 1182, 920), { x: 1, y: 1 });
});

test('pointers take the free lower slot and keep it until they lift', () => {
  const slots = createTouchSlots();
  assert.equal(slots.down(7, { x: 0.1, y: 0.2 }), true);
  assert.equal(slots.down(7, { x: 0.1, y: 0.2 }), false);
  assert.equal(slots.down(8, { x: 0.3, y: 0.4 }), true);
  assert.equal(slots.down(9, { x: 0.5, y: 0.5 }), false, 'a third pointer is ignored');
  assert.deepEqual(slots.contacts(), [[0, 0.1, 0.2, 1], [1, 0.3, 0.4, 1]]);
  assert.deepEqual(slots.up(7), [[0, 0.1, 0.2, 0], [1, 0.3, 0.4, 1]]);
  assert.equal(slots.up(7), null);
  slots.move(8, { x: 0.123456, y: 0.9 });
  assert.equal(slots.down(10, { x: 0.6, y: 0.7 }), true);
  assert.deepEqual(slots.contacts(), [[0, 0.6, 0.7, 1], [1, 0.1235, 0.9, 1]], 'the new pointer reuses slot 0');
  assert.deepEqual(slots.releaseAll(), [[0, 0.6, 0.7, 0], [1, 0.1235, 0.9, 0]]);
  assert.equal(slots.size, 0);
  assert.deepEqual(slots.releaseAll(), []);
});

function outboxFixture(options = {}) {
  let time = 0;
  const posts = [], errors = [], statuses = [];
  const outbox = createControlOutbox({
    now: () => time,
    send: (q, events) => new Promise((resolve, reject) => posts.push({ q, events, resolve, reject })),
    onStatus: status => statuses.push(status),
    onError: error => errors.push(error),
    ...options,
  });
  const settle = () => new Promise(resolve => setImmediate(resolve));
  return {
    outbox, posts, errors, statuses, settle,
    advance: ms => { time += ms; },
    async answer(status = { state: 'streaming' }) {
      const post = posts.find(entry => !entry.done);
      post.done = true;
      post.resolve(status);
      await settle();
    },
  };
}
const touch = (x, down = 1) => ({ k: 't', p: [[0, x, 0.5, down]] });

test('exactly one control POST is in flight and the next carries everything queued meanwhile', async () => {
  const f = outboxFixture();
  f.outbox.push(touch(0));
  await f.settle();
  f.outbox.push({ k: 'kf' });
  f.outbox.push(touch(0.5, 0));
  await f.settle();
  assert.equal(f.posts.length, 1);
  assert.deepEqual(f.posts[0], { ...f.posts[0], q: 1, events: [touch(0)] });
  f.advance(25);
  await f.answer({ q: 1 });
  assert.equal(f.posts.length, 2);
  assert.equal(f.posts[1].q, 2);
  assert.deepEqual(f.posts[1].events, [{ k: 'kf' }, touch(0.5, 0)]);
  assert.deepEqual(f.statuses, [{ q: 1 }]);
  await f.answer();
  assert.equal(f.posts.length, 2, 'nothing is sent without events or a heartbeat');
  assert.equal(f.outbox.stats().rttMsP50, 0);
});

test('moves merge only within a gesture and down/up edges stay ordered', async () => {
  const f = outboxFixture();
  f.outbox.push({ k: 'vp', w: 1 }, 'latest');
  await f.settle();
  f.outbox.push(touch(0));
  for (let i = 1; i <= 100; i++) f.outbox.push(touch(i / 100), 'move');
  f.outbox.push(touch(1, 0));
  f.outbox.push(touch(0.2));
  f.outbox.push(touch(0.3), 'move');
  f.outbox.push({ k: 'vp', w: 2 }, 'latest');
  f.outbox.push({ k: 'vp', w: 3 }, 'latest');
  f.outbox.push({ k: 't', p: [] });
  await f.answer();
  assert.deepEqual(f.posts[1].events, [touch(0), touch(1), touch(1, 0), touch(0.2), touch(0.3), { k: 'vp', w: 3 }, { k: 't', p: [] }]);
});

test('the heartbeat re-sends held contacts and otherwise posts an empty batch', async () => {
  let held = [[0, 0.4, 0.6, 1]];
  const f = outboxFixture({ heldContacts: () => held });
  f.outbox.heartbeat();
  await f.settle();
  assert.deepEqual(f.posts[0].events, [{ k: 't', p: held }]);
  f.outbox.heartbeat();
  f.outbox.heartbeat();
  await f.answer();
  assert.deepEqual(f.posts[1].events, [{ k: 't', p: held }], 'heartbeats during a POST merge into one');
  held = [];
  await f.answer();
  f.outbox.heartbeat();
  await f.settle();
  assert.deepEqual(f.posts[2].events, []);
  assert.equal(f.posts[2].q, 3);
});

test('a failed POST is reported and not replayed at once', async () => {
  const f = outboxFixture();
  f.outbox.push(touch(0));
  await f.settle();
  f.outbox.push(touch(0.1, 0));
  f.posts[0].done = true;
  f.posts[0].reject(Object.assign(new Error('gone'), { status: 503 }));
  await f.settle();
  assert.equal(f.errors[0].status, 503);
  assert.deepEqual(f.posts.map(post => post.events), [[touch(0)], [touch(0.1, 0)]]);
  assert.deepEqual(f.posts.map(post => post.q), [1, 2]);
  assert.equal(f.outbox.stats().failures, 1);
});

const fail = async f => {
  await f.settle();
  const post = f.posts.find(entry => !entry.done);
  post.done = true;
  post.reject(new TypeError('Failed to fetch'));
  await f.settle();
};

test('the heartbeat after a failed POST repeats its latest events and lifts a finger whose up edge was lost', async () => {
  // A viewport lost while the phone was unreachable would otherwise never be sent again (it is deduplicated),
  // a lost dec leaves the phone without its connection proof, and a lost lift holds the finger for 1.5 s.
  const f = outboxFixture();
  f.outbox.push(touch(0.2));
  await f.settle();
  f.outbox.push({ k: 'vp', w: 1182 }, 'latest');
  f.outbox.push({ k: 'dec', ep: 3 }, 'latest');
  f.outbox.push(touch(0.2, 0));
  f.outbox.push({ k: 'fit' });
  await f.answer();
  await fail(f);
  assert.equal(f.posts.length, 2, 'nothing is resent before the heartbeat');
  f.outbox.heartbeat();
  await f.settle();
  assert.deepEqual(f.posts[2].events, [{ k: 'vp', w: 1182 }, { k: 'dec', ep: 3 }, { k: 't', p: [] }], 'fit is an action and is not repeated');
  await f.answer();
  f.outbox.heartbeat();
  await f.settle();
  assert.deepEqual(f.posts[3].events, [], 'a delivered repeat is not sent again');
});

test('a failed event is not repeated over a newer one of the same kind', async () => {
  let held = [];
  const f = outboxFixture({ heldContacts: () => held });
  f.outbox.push({ k: 'vp', w: 1 }, 'latest');
  await f.settle();
  f.outbox.push({ k: 'vp', w: 2 }, 'latest');
  await fail(f);
  f.outbox.heartbeat();
  await f.answer();
  assert.deepEqual(f.posts.map(post => post.events), [[{ k: 'vp', w: 1 }], [{ k: 'vp', w: 2 }], []], 'vp 2 was queued while vp 1 failed');
  await f.answer();

  f.outbox.push({ k: 'vp', w: 3 }, 'latest');
  await fail(f);
  f.outbox.push({ k: 'vp', w: 4 }, 'latest');
  await f.settle();
  await f.answer();
  f.outbox.push(touch(0.5));
  await fail(f);
  held = [[0, 0.6, 0.5, 1]];
  f.outbox.push({ k: 't', p: held }, 'move');
  await f.settle();
  await f.answer();
  f.outbox.heartbeat();
  await f.settle();
  assert.deepEqual(f.posts.slice(3).map(post => post.events),
    [[{ k: 'vp', w: 3 }], [{ k: 'vp', w: 4 }], [touch(0.5)], [{ k: 't', p: held }], [{ k: 't', p: held }]]);
});

test('the queue is bounded at 64 events', async () => {
  const f = outboxFixture();
  f.outbox.push(touch(0));
  await f.settle();
  for (let i = 0; i < 64; i++) assert.equal(f.outbox.push(touch(i, i % 2)), true);
  assert.equal(f.outbox.push(touch(1)), false);
  assert.equal(f.errors.length, 1);
  assert.equal(f.outbox.stats().pending, 0);
  assert.equal(f.outbox.push(touch(2)), true, 'the outbox keeps working after an overflow');
  await f.answer();
  assert.deepEqual(f.posts[1].events, [touch(2)]);
});

test('a closed outbox ignores the answer in flight and sends nothing more', async () => {
  const f = outboxFixture();
  f.outbox.push(touch(0));
  await f.settle();
  f.outbox.close();
  assert.equal(f.outbox.push(touch(1)), false);
  await f.answer();
  assert.deepEqual(f.statuses, []);
  assert.equal(f.posts.length, 1);
});

test('viewport is CSS × DPR rounded to even and only within the phone limits', () => {
  assert.deepEqual(viewportFor(773, 601, 1.53), { w: 1182, h: 920, cw: 773, ch: 601, dpr: 1.53 });
  assert.deepEqual(viewportFor(773.4, 545, 1), { w: 774, h: 546, cw: 773, ch: 545, dpr: 1 });
  assert.equal(viewportFor(300, 800, 1), null, 'too narrow');
  assert.equal(viewportFor(2100, 1000, 2), null, 'too wide');
  assert.equal(viewportFor(2000, 400, 1), null, 'aspect above 4');
  assert.equal(viewportFor(400, 900, 1), null, 'aspect below 0.5');
  assert.deepEqual(viewportFor(400, 800, 1), { w: 400, h: 800, cw: 400, ch: 800, dpr: 1 });
  assert.deepEqual(viewportFor(640, 480, 0), { w: 640, h: 480, cw: 640, ch: 480, dpr: 1 });
});

test('codec probing lists the families the decoder accepts', async () => {
  const seen = [];
  const supports = async config => {
    seen.push(config);
    if (config.codec.startsWith('hvc1')) throw new TypeError('invalid');
    return { supported: config.codec !== 'av01.0.08M.08' };
  };
  assert.deepEqual(await supportedCodecs(supports), ['avc1', 'vp8', 'vp09']);
  assert.deepEqual(seen[0], { codec: 'avc1.640028', codedWidth: 1280, codedHeight: 720 });
});

test('the decoder config prefers hardware and falls back to any decoder', async () => {
  const record = { codec: 'vp8', width: 800, height: 480 };
  const expected = { codec: 'vp8', codedWidth: 800, codedHeight: 480, optimizeForLatency: true };
  assert.deepEqual(await chooseDecoderConfig(async () => ({ supported: true }), record), { ...expected, hardwareAcceleration: 'prefer-hardware' });
  const softwareOnly = async config => ({ supported: config.hardwareAcceleration !== 'prefer-hardware' });
  assert.deepEqual(await chooseDecoderConfig(softwareOnly, record), { ...expected, hardwareAcceleration: 'no-preference' });
  assert.equal(await chooseDecoderConfig(async () => ({ supported: false }), record), null);
  assert.equal(await chooseDecoderConfig(async () => { throw new TypeError('bad codec'); }, record), null);
});

test('samples report nearest-rank percentiles over a bounded window', () => {
  const samples = new Samples(240);
  assert.equal(samples.percentile(50), null);
  for (let i = 0; i < 300; i++) samples.add(i);
  assert.equal(samples.percentile(50), 179);
  assert.equal(samples.percentile(95), 287);
  assert.equal(samples.percentile(100), 299);
});

test('stats events are flat, rounded and at most 2 KiB', () => {
  const flat = flatStats({ video: { fps: 59.987, codec: 'avc1.640028', live: true, list: [1], none: null, bad: NaN }, n: 1 });
  assert.deepEqual(flat, { 'video.fps': 59.99, 'video.codec': 'avc1.640028', 'video.live': 1, n: 1 });
  const many = Object.fromEntries(Array.from({ length: 500 }, (_, i) => [`counter${i}`, i * 1000.5]));
  assert.ok(JSON.stringify(flatStats(many)).length <= 2048);
  assert.equal(flatStats({ s: 'x'.repeat(100) }).s.length, 40);
});
