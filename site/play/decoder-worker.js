/*! SPDX-License-Identifier: GPL-3.0-only
 * TeslaPlay decoder worker (see video.js).
 * In:  {type:'canvas', canvas} · {type:'resize', width, height} · {type:'stream', id, stream} · {type:'stop'}
 *      {type:'feed', id}, {type:'chunk', id, chunk}, {type:'feed-end', id, error}: the main thread pumps the body
 *      when streams are not transferable.
 * Out: the pipeline's messages · {type:'frame', frame, config} when the main thread draws (no OffscreenCanvas)
 *      {type:'stats', stats} every second · {type:'ready', videoDecoder} once at start. */
import { createDecoderPipeline, createFramePresenter } from './video.js';

let presenter = null;
const feeds = new Map();
const pipeline = typeof VideoDecoder === 'function' ? createDecoderPipeline({
  post: message => self.postMessage(message),
  present: (frame, config) => {
    if (presenter) presenter.present(frame, config);
    else transferFrame(frame, config);
  },
}) : null;

function transferFrame(frame, config) {
  try {
    self.postMessage({ type: 'frame', frame, config }, [frame]);
  } catch (_) {
    frame.close();
  }
}

function feed(id) {
  return new ReadableStream({
    start: controller => { feeds.set(id, controller); },
    cancel: () => { feeds.delete(id); },
  });
}

function endFeed(id, error) {
  const controller = feeds.get(id);
  feeds.delete(id);
  if (error) controller?.error(new TypeError(error));
  else controller?.close();
}

self.onmessage = ({ data }) => {
  if (data.type === 'canvas') presenter = createFramePresenter(data.canvas);
  else if (data.type === 'resize') presenter?.resize(data.width, data.height);
  else if (data.type === 'stream') pipeline?.read(data.id, data.stream);
  else if (data.type === 'feed') pipeline?.read(data.id, feed(data.id));
  else if (data.type === 'chunk') feeds.get(data.id)?.enqueue(data.chunk);
  else if (data.type === 'feed-end') endFeed(data.id, data.error);
  else if (data.type === 'stop') pipeline?.stop();
};

setInterval(() => {
  if (pipeline) self.postMessage({ type: 'stats', stats: { ...pipeline.stats(), ...presenter?.stats() } });
}, 1000);
self.postMessage({ type: 'ready', videoDecoder: Boolean(pipeline) });
