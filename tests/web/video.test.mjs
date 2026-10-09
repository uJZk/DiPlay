// SPDX-License-Identifier: GPL-3.0-only
// The WebCodecs pipeline of video.js against a scripted VideoDecoder.
import test from 'node:test';
import assert from 'node:assert/strict';
import { FLAG, RECORD, encodeRecord } from '../../site/play/link.js';
import { createDecoderPipeline } from '../../site/play/video.js';

const settle = () => new Promise(resolve => setImmediate(resolve));

class FakeDecoder {
  static instances = [];
  static gate = null;

  static isConfigSupported(config) {
    return (FakeDecoder.gate ?? Promise.resolve()).then(() => ({ supported: true, config }));
  }

  constructor({ output }) {
    this.output = output;
    this.state = 'unconfigured';
    this.decodeQueueSize = 0;
    this.chunks = [];
    FakeDecoder.instances.push(this);
  }

  configure() { this.state = 'configured'; }
  decode(chunk) { this.chunks.push(chunk); }
  close() { this.state = 'closed'; }
}

const config = epoch => encodeRecord({
  kind: RECORD.CONFIG, codec: 1, epoch, payload: new TextEncoder().encode(JSON.stringify({ codec: 'avc1.640028', width: 1182, height: 920, fps: 60 })),
});
const keyFrame = epoch => encodeRecord({ kind: RECORD.FRAME, flags: FLAG.KEY, codec: 1, epoch, payload: Uint8Array.of(0, 0, 0, 1, 0x65) });
const videoFrame = timestamp => ({ timestamp, closed: false, close() { this.closed = true; } });

test('late output of the previous decoder is not drawn or reported as the new epoch', async t => {
  t.mock.timers.enable({ apis: ['setInterval'] });
  globalThis.VideoDecoder = FakeDecoder;
  globalThis.EncodedVideoChunk = class { constructor(init) { Object.assign(this, init); } };
  t.after(() => { delete globalThis.VideoDecoder; delete globalThis.EncodedVideoChunk; });

  const messages = [], presented = [];
  const pipeline = createDecoderPipeline({ post: message => messages.push(message), present: frame => presented.push(frame) });
  let controller;
  pipeline.read(1, new ReadableStream({ start(c) { controller = c; } }));
  const decoded = () => messages.filter(message => message.type === 'decoded').map(message => message.epoch);

  controller.enqueue(config(1));
  controller.enqueue(keyFrame(1));
  await settle();
  const [first] = FakeDecoder.instances;
  assert.equal(first.chunks.length, 1);
  first.output(videoFrame(0));
  assert.deepEqual(decoded(), [1]);

  // The phone changes the configuration while the old decoder still holds a frame.
  let open;
  FakeDecoder.gate = new Promise(resolve => { open = resolve; });
  controller.enqueue(config(2));
  await settle();
  const late = videoFrame(16666);
  if (first.state !== 'closed') first.output(late);
  assert.deepEqual(decoded(), [1], 'a frame of epoch 1 is no proof that epoch 2 renders');
  assert.equal(presented.includes(late), false);

  FakeDecoder.gate = null;
  open();
  await settle();
  controller.enqueue(keyFrame(2));
  await settle();
  const second = FakeDecoder.instances.at(-1);
  assert.notEqual(second, first);
  assert.equal(second.chunks.length, 1);
  second.output(videoFrame(0));
  assert.deepEqual(decoded(), [1, 2]);
  pipeline.stop();
});
