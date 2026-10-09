/*! SPDX-License-Identifier: GPL-3.0-only
 * TeslaPlay MSE fallback (contract §7.2, rung 4): §3 records → fragmented MP4 → <video>. For browsers without
 * WebCodecs or outside a secure context, such as the page the phone serves over plain HTTP. */
import { FLAG, RECORD, RecordError, createRecordStream } from './link.js';
import { TIMESCALE, annexBToLengthPrefixed, initSegment, mediaSegment, mseType } from './fmp4.js';

const LIVE_EDGE_S = 0.15; // seek to the end of the buffer when playback falls further behind
const KEEP_S = 4; // buffer kept behind the playhead
const MAX_QUEUE = 30; // appends waiting for the SourceBuffer before dropping to the next key frame
const KEY_REQUEST_INTERVAL_MS = 1000;
const MediaSourceType = window.ManagedMediaSource ?? window.MediaSource;
const utf8 = new TextDecoder();

const base64Bytes = text => Uint8Array.from(atob(String(text ?? '')), char => char.charCodeAt(0));

/** Plays record streams on `video`. `post(message)` gets the same messages as the WebCodecs pipeline. */
export function createMsePlayer(video, { post }) {
  const totals = { appended: 0, dropped: 0, seeks: 0, backlogResets: 0, appendErrors: 0, mediaErrors: 0, keyRequests: 0 };
  let media = null, generation = 0, needKey = true, reportedEpoch = null, lastKeyRequest = -Infinity, codecSupported = null;
  let lastConfig = null, streamId = null, rates = { decodedFps: 0, bytesPerSec: 0 }, lastTick = { at: performance.now(), frames: 0, bytes: 0 };
  const records = createRecordStream({
    onRecord: record => {
      if (record.kind === RECORD.CONFIG) return configure(record);
      if (record.kind === RECORD.FRAME) append(record);
      return null;
    },
    onAlive: id => post({ type: 'alive', id }),
    onEnd: (id, reason) => post({ type: 'end', id, reason }),
  });
  video.muted = true;
  video.playsInline = true;
  video.disableRemotePlayback = true; // ManagedMediaSource needs it
  video.addEventListener('loadeddata', reportDecoded);
  // A decode error ends the media element for good, and every later append throws. Drop it and reconnect:
  // the new stream's config record builds a fresh one.
  video.addEventListener('error', () => {
    if (!media) return;
    totals.mediaErrors++;
    close();
    records.stop();
    post({ type: 'end', id: streamId, reason: 'media-error' });
  });

  const shownFrames = () => video.getVideoPlaybackQuality?.().totalVideoFrames ?? video.webkitDecodedFrameCount ?? 0;
  setInterval(() => {
    records.watchdog();
    const now = performance.now(), seconds = (now - lastTick.at) / 1000, frames = shownFrames(), bytes = records.stats().bytes;
    rates = { decodedFps: Math.round((frames - lastTick.frames) / seconds * 10) / 10, bytesPerSec: Math.round((bytes - lastTick.bytes) / seconds) };
    lastTick = { at: now, frames, bytes };
  }, 1000);

  async function configure(record) {
    let info;
    try {
      info = JSON.parse(utf8.decode(record.payload));
    } catch (_) {
      throw new RecordError('config is not JSON');
    }
    const next = { epoch: record.epoch, codec: String(info.codec), width: info.width | 0, height: info.height | 0, fps: info.fps | 0 };
    post({ type: 'config', config: next });
    lastConfig = next;
    if (media && ['epoch', 'codec', 'width', 'height'].every(key => media.config[key] === next[key])) return;
    close();
    const type = mseType(next.codec);
    let init = null;
    try {
      init = MediaSourceType.isTypeSupported(type) ? initSegment({ ...next, record: base64Bytes(info.record) }) : null;
    } catch (_) {
      // a malformed base64 record: the codec cannot be set up
    }
    codecSupported = Boolean(init);
    if (!init) {
      post({ type: 'unsupported', codec: next.codec });
      return;
    }
    const mine = ++generation;
    const source = new MediaSourceType();
    const url = URL.createObjectURL(source);
    const opened = new Promise(resolve => source.addEventListener('sourceopen', resolve, { once: true }));
    video.src = url;
    await opened;
    if (mine !== generation) return;
    const buffer = source.addSourceBuffer(type);
    media = {
      config: next, buffer, url, queue: [], trimming: false, sequence: 0, decodeTime: 0,
      duration: Math.round(TIMESCALE / (next.fps || 60)), lengthPrefixed: !next.codec.startsWith('vp'),
    };
    const current = media;
    buffer.addEventListener('updateend', () => {
      if (media !== current) return;
      chaseLiveEdge();
      reportDecoded();
      pump();
    });
    reportedEpoch = null;
    needKey = true;
    enqueue(init);
    video.play().catch(() => {});
  }

  function close() {
    generation++;
    if (!media) return;
    URL.revokeObjectURL(media.url);
    media = null;
    video.removeAttribute('src');
    video.load();
  }

  function requestKeyFrame(force = false) {
    const now = performance.now();
    if (!force && now - lastKeyRequest < KEY_REQUEST_INTERVAL_MS) return;
    lastKeyRequest = now;
    totals.keyRequests++;
    post({ type: 'keyframe' });
  }

  /** Frames get consecutive decode times one nominal frame apart, so a still CarPlay screen leaves no gap to stall on. */
  function append(record) {
    const key = (record.flags & FLAG.KEY) !== 0;
    if (!media || (needKey && !key)) {
      totals.dropped++;
      if (media) requestKeyFrame();
      return;
    }
    if (media.queue.length > MAX_QUEUE) {
      totals.backlogResets++;
      media.queue.length = 0;
      needKey = true;
      if (!key) {
        totals.dropped++;
        requestKeyFrame(true);
        return;
      }
    }
    const data = media.lengthPrefixed ? annexBToLengthPrefixed(record.payload) : record.payload;
    if (!data) {
      totals.dropped++;
      return;
    }
    needKey = false;
    enqueue(mediaSegment({ sequence: ++media.sequence, decodeTime: media.decodeTime, duration: media.duration, data, key }));
    media.decodeTime += media.duration;
  }

  function enqueue(segment) {
    media.queue.push(segment);
    pump();
  }

  function pump() {
    if (!media || media.buffer.updating || !media.queue.length) return;
    const next = media.queue.shift();
    try {
      if (next.remove) {
        media.trimming = false;
        media.buffer.remove(...next.remove);
      } else {
        media.buffer.appendBuffer(next);
        totals.appended++;
      }
    } catch (_) {
      // QuotaExceededError or a source the browser closed: start again at the next key frame.
      totals.appendErrors++;
      media.queue.length = 0;
      needKey = true;
      requestKeyFrame(true);
    }
  }

  function chaseLiveEdge() {
    const { buffered } = media.buffer;
    if (!buffered.length) return;
    const start = buffered.start(buffered.length - 1), end = buffered.end(buffered.length - 1);
    if (end - video.currentTime > LIVE_EDGE_S || video.currentTime < start) {
      video.currentTime = Math.max(start, end - media.duration / TIMESCALE / 2);
      totals.seeks++;
    }
    if (!media.trimming && video.currentTime - buffered.start(0) > 2 * KEEP_S) {
      media.trimming = true;
      media.queue.unshift({ remove: [0, video.currentTime - KEEP_S] });
    }
    if (video.paused) video.play().catch(() => {});
  }

  function reportDecoded() {
    if (!media || reportedEpoch === media.config.epoch || video.readyState < 2) return;
    reportedEpoch = media.config.epoch;
    post({ type: 'decoded', epoch: reportedEpoch });
  }

  return {
    read(id, stream) {
      streamId = id;
      needKey = true;
      reportedEpoch = null;
      records.read(id, stream);
    },
    stop: () => records.stop(),
    stats() {
      const buffered = media?.buffer.buffered;
      return {
        renderer: 'video',
        codec: lastConfig?.codec ?? null,
        codecSupported,
        width: lastConfig?.width ?? null,
        height: lastConfig?.height ?? null,
        ...rates,
        bufferMs: buffered?.length ? Math.round((buffered.end(buffered.length - 1) - video.currentTime) * 1000) : null,
        appendQueue: media?.queue.length ?? 0,
        droppedVideoFrames: video.getVideoPlaybackQuality?.().droppedVideoFrames ?? null,
        ...records.stats(),
        ...totals,
      };
    },
  };
}
