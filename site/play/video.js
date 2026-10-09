/*! SPDX-License-Identifier: GPL-3.0-only
 * TeslaPlay WebCodecs video: §3 records → VideoDecoder → frames, and a presenter that draws frames on a canvas
 * (WebGL, 2D as a fallback). Runs in decoder-worker.js on the Tesla path, or on the main thread as a fallback. */
import { FLAG, RECORD, RecordError, Samples, chooseDecoderConfig, createRecordStream, letterbox } from './link.js';

const KEY_REQUEST_INTERVAL_MS = 1000;
const MAX_DECODE_QUEUE = 30; // half a second at 60 fps; beyond that, drop to the next key frame instead of lagging
const MAX_FAILURES = 4; // decoder errors without a decoded frame before the page gives up on this config
const utf8 = new TextDecoder();

/**
 * Decodes record streams. `post(message)` gets {type:'alive'|'config'|'decoded'|'keyframe'|'unsupported'|'end'};
 * `present(frame)` takes ownership of every decoded VideoFrame.
 */
export function createDecoderPipeline({ post, present }) {
  const decoderSupports = config => VideoDecoder.isConfigSupported(config);
  const decodeStarts = new Map();
  const decodeMs = new Samples();
  const totals = { decoded: 0, dropped: 0, decodeErrors: 0, backlogResets: 0, keyRequests: 0 };
  let decoder = null, decoderConfig = null, config = null, codecSupported = null;
  let needKey = true, reportedEpoch = null, lastKeyRequest = -Infinity, failures = 0;
  const records = createRecordStream({
    onRecord: record => {
      if (record.kind === RECORD.CONFIG) return configure(record);
      if (record.kind === RECORD.FRAME) decodeFrame(record);
      return null;
    },
    onAlive: id => post({ type: 'alive', id }),
    onEnd: (id, reason) => post({ type: 'end', id, reason }),
  });
  let rates = { decodedFps: 0, bytesPerSec: 0 }, lastTick = { at: performance.now(), decoded: 0, bytes: 0 };
  setInterval(() => {
    records.watchdog();
    const now = performance.now(), seconds = (now - lastTick.at) / 1000, bytes = records.stats().bytes;
    rates = { decodedFps: Math.round((totals.decoded - lastTick.decoded) / seconds * 10) / 10, bytesPerSec: Math.round((bytes - lastTick.bytes) / seconds) };
    lastTick = { at: now, decoded: totals.decoded, bytes };
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
    // The phone repeats a byte-identical config at every stream start; keep the decoder then.
    const same = config && decoder?.state === 'configured' && ['epoch', 'codec', 'width', 'height'].every(key => config[key] === next[key]);
    config = next;
    if (same) return;
    reportedEpoch = null;
    // Late output of the old decoder would otherwise be drawn and reported as the first frame of the new epoch.
    closeDecoder();
    const chosen = await chooseDecoderConfig(decoderSupports, next);
    if (config !== next) return; // a newer config arrived meanwhile
    decoderConfig = chosen;
    codecSupported = Boolean(chosen);
    failures = 0;
    if (!decoderConfig) {
      closeDecoder();
      post({ type: 'unsupported', codec: next.codec });
      return;
    }
    openDecoder();
  }

  function openDecoder() {
    closeDecoder();
    const created = new VideoDecoder({
      output: frame => onFrame(created, frame),
      error: () => {
        if (created === decoder) failed();
      },
    });
    decoder = created;
    decoder.configure(decoderConfig);
    needKey = true;
  }

  function closeDecoder() {
    if (decoder && decoder.state !== 'closed') decoder.close();
    decoder = null;
    decodeStarts.clear();
  }

  /**
   * A decoder that reported an error is closed, so build a new one; the picture resumes at the next key frame.
   * Repeated errors without a decoded frame suggest a broken hardware path: try any decoder, then give up.
   */
  function failed() {
    totals.decodeErrors++;
    failures++;
    if (failures > MAX_FAILURES) {
      closeDecoder();
      codecSupported = false;
      post({ type: 'unsupported', codec: decoderConfig.codec });
      return;
    }
    if (failures > 1) decoderConfig = { ...decoderConfig, hardwareAcceleration: 'no-preference' };
    openDecoder();
    requestKeyFrame(true);
  }

  function requestKeyFrame(force = false) {
    const now = performance.now();
    if (!force && now - lastKeyRequest < KEY_REQUEST_INTERVAL_MS) return;
    lastKeyRequest = now;
    totals.keyRequests++;
    post({ type: 'keyframe' });
  }

  function decodeFrame(record) {
    const key = (record.flags & FLAG.KEY) !== 0;
    if (decoder?.state !== 'configured' || (needKey && !key)) {
      totals.dropped++;
      if (decoder) requestKeyFrame();
      return;
    }
    if (decoder.decodeQueueSize > MAX_DECODE_QUEUE) {
      totals.backlogResets++;
      decoder.reset();
      decoder.configure(decoderConfig);
      decodeStarts.clear();
      needKey = true;
      if (!key) {
        totals.dropped++;
        requestKeyFrame(true);
        return;
      }
    }
    needKey = false;
    decodeStarts.set(record.timestampUs, performance.now());
    if (decodeStarts.size > 120) decodeStarts.delete(decodeStarts.keys().next().value);
    try {
      decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: record.timestampUs, data: record.payload }));
    } catch (_) {
      failed();
    }
  }

  function onFrame(source, frame) {
    if (source !== decoder) {
      frame.close();
      return;
    }
    const started = decodeStarts.get(frame.timestamp);
    if (started !== undefined) {
      decodeStarts.delete(frame.timestamp);
      decodeMs.add(performance.now() - started);
    }
    totals.decoded++;
    failures = 0;
    present(frame, config);
    if (config && reportedEpoch !== config.epoch) {
      reportedEpoch = config.epoch;
      post({ type: 'decoded', epoch: config.epoch });
    }
  }

  return {
    /** Every stream starts with a config record and a key frame; outputs still in the decoder are harmless. */
    read(id, stream) {
      needKey = true;
      reportedEpoch = null;
      records.read(id, stream);
    },
    stop: () => records.stop(),
    stats: () => ({
      codec: config?.codec ?? null,
      codecSupported,
      width: config?.width ?? null,
      height: config?.height ?? null,
      acceleration: decoderConfig?.hardwareAcceleration ?? null,
      decodeQueueSize: decoder?.decodeQueueSize ?? 0,
      ...rates,
      decodeMsP50: decodeMs.percentile(50),
      decodeMsP95: decodeMs.percentile(95),
      ...records.stats(),
      ...totals,
    }),
  };
}

/**
 * Draws frames letterboxed with the aspect of the negotiated CarPlay canvas, which is what touch mapping assumes.
 * It keeps the last frame (closing the one before) so a resize can redraw a still CarPlay screen.
 */
export function createFramePresenter(canvas) {
  const drawMs = new Samples();
  let renderer = createRenderer(canvas), lastFrame = null, lastConfig = null;

  function draw(frame) {
    const width = lastConfig?.width || frame.displayWidth, height = lastConfig?.height || frame.displayHeight;
    renderer.draw(frame, letterbox(canvas.width, canvas.height, width, height));
  }

  if (renderer.kind !== '2d') {
    canvas.addEventListener('webglcontextlost', event => event.preventDefault());
    canvas.addEventListener('webglcontextrestored', () => {
      renderer = createRenderer(canvas);
      if (lastFrame) draw(lastFrame);
    });
  }

  return {
    present(frame, config) {
      lastConfig = config;
      const started = performance.now();
      draw(frame);
      drawMs.add(performance.now() - started);
      lastFrame?.close();
      lastFrame = frame;
    },
    resize(width, height) {
      if (width < 1 || height < 1 || (canvas.width === width && canvas.height === height)) return;
      canvas.width = width;
      canvas.height = height;
      if (lastFrame) draw(lastFrame);
    },
    close() {
      lastFrame?.close();
      lastFrame = null;
    },
    stats: () => ({ renderer: renderer.kind, canvas: `${canvas.width}x${canvas.height}`, drawMsP95: drawMs.percentile(95) }),
  };
}

function createRenderer(canvas) {
  const options = { alpha: false, antialias: false, depth: false, desynchronized: true, powerPreference: 'high-performance' };
  const gl = canvas.getContext('webgl2', options) || canvas.getContext('webgl', options);
  if (gl) return glRenderer(gl);
  const context = canvas.getContext('2d', { alpha: false, desynchronized: true });
  return {
    kind: '2d',
    draw(frame, box) {
      context.fillStyle = '#000';
      context.fillRect(0, 0, canvas.width, canvas.height);
      context.drawImage(frame, box.x, box.y, box.width, box.height);
    },
  };
}

function glRenderer(gl) {
  const vertex = 'attribute vec2 p;varying vec2 uv;void main(){uv=vec2(p.x+1.,1.-p.y)*.5;gl_Position=vec4(p,0.,1.);}';
  const fragment = 'precision mediump float;varying vec2 uv;uniform sampler2D t;void main(){gl_FragColor=texture2D(t,uv);}';
  const program = gl.createProgram();
  for (const [type, source] of [[gl.VERTEX_SHADER, vertex], [gl.FRAGMENT_SHADER, fragment]]) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    gl.attachShader(program, shader);
  }
  gl.linkProgram(program);
  gl.useProgram(program);
  gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  const position = gl.getAttribLocation(program, 'p');
  gl.enableVertexAttribArray(position);
  gl.vertexAttribPointer(position, 2, gl.FLOAT, false, 0, 0);
  gl.bindTexture(gl.TEXTURE_2D, gl.createTexture());
  for (const [name, value] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR],
    [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, name, value);
  gl.clearColor(0, 0, 0, 1);
  return {
    kind: typeof WebGL2RenderingContext !== 'undefined' && gl instanceof WebGL2RenderingContext ? 'webgl2' : 'webgl',
    draw(frame, box) {
      if (gl.isContextLost()) return;
      const { width, height } = gl.canvas;
      gl.viewport(0, 0, width, height);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, frame);
      gl.viewport(box.x, height - box.y - box.height, box.width, box.height);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    },
  };
}
