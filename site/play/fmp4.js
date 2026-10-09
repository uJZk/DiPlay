/*! SPDX-License-Identifier: GPL-3.0-only
 * Minimal fragmented MP4 for the MSE fallback (contract §7.2): an init segment built from the §3 config record and
 * one moof/mdat fragment per access unit. Sample entries: avc1/avc3 (avcC), hvc1/hev1 (hvcC) and vp09 (vpcC). */

export const TIMESCALE = 90000;
const MATRIX = [0x10000, 0, 0, 0, 0x10000, 0, 0, 0, 0x40000000];

const ascii = text => Uint8Array.from(text, char => char.charCodeAt(0));
const zeros = length => new Uint8Array(length);

function int(bytes, value) {
  const out = new Uint8Array(bytes), view = new DataView(out.buffer);
  if (bytes === 1) view.setUint8(0, value);
  else if (bytes === 2) view.setUint16(0, value);
  else if (bytes === 4) view.setUint32(0, value >>> 0);
  else {
    view.setUint32(0, Math.floor(value / 2 ** 32));
    view.setUint32(4, value >>> 0);
  }
  return out;
}
const u8 = value => int(1, value), u16 = value => int(2, value), u32 = value => int(4, value), u64 = value => int(8, value);

function concat(parts) {
  const out = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let offset = 0;
  for (const part of parts) {
    out.set(part, offset);
    offset += part.length;
  }
  return out;
}

function box(type, ...parts) {
  const body = concat(parts);
  return concat([u32(8 + body.length), ascii(type), body]);
}

const fullBox = (type, version, flags, ...parts) => box(type, u32(version * 2 ** 24 + flags), ...parts);

export const mseType = codec => `video/mp4; codecs="${codec}"`;

/** `vp09.PP.LL.DD[.CC.cp.tc.mc.FF]` with the defaults of the VP9 ISO-BMFF binding. */
function vpcC(codec) {
  const [, profile, level, bitDepth, chroma = 1, primaries = 1, transfer = 1, matrix = 1, fullRange = 0] = codec.split('.').map(Number);
  if (![profile, level, bitDepth].every(Number.isInteger)) return null;
  return fullBox('vpcC', 1, 0, u8(profile), u8(level), u8((bitDepth << 4) | (chroma << 1) | fullRange),
    u8(primaries), u8(transfer), u8(matrix), u16(0));
}

function configBox(codec, record) {
  const family = codec.split('.')[0];
  if (family === 'avc1' || family === 'avc3') return record?.length ? box('avcC', record) : null;
  if (family === 'hvc1' || family === 'hev1') return record?.length ? box('hvcC', record) : null;
  if (family === 'vp09') return vpcC(codec);
  return null;
}

/** ftyp + moov for one video track, or null for a codec this muxer cannot carry. `record` is the avcC/hvcC body. */
export function initSegment({ codec, width, height, record }) {
  const config = configBox(codec, record);
  if (!config) return null;
  const entry = box(codec.split('.')[0], zeros(6), u16(1), zeros(16), u16(width), u16(height),
    u32(0x480000), u32(0x480000), u32(0), u16(1), zeros(32), u16(0x18), u16(0xffff), config);
  const stbl = box('stbl', fullBox('stsd', 0, 0, u32(1), entry), fullBox('stts', 0, 0, u32(0)), fullBox('stsc', 0, 0, u32(0)),
    fullBox('stsz', 0, 0, u32(0), u32(0)), fullBox('stco', 0, 0, u32(0)));
  const minf = box('minf', fullBox('vmhd', 0, 1, zeros(8)), box('dinf', fullBox('dref', 0, 0, u32(1), fullBox('url ', 0, 1))), stbl);
  const mdia = box('mdia', fullBox('mdhd', 0, 0, u32(0), u32(0), u32(TIMESCALE), u32(0), u16(0x55c4), u16(0)),
    fullBox('hdlr', 0, 0, u32(0), ascii('vide'), zeros(12), ascii('TeslaPlay\0')), minf);
  const tkhd = fullBox('tkhd', 0, 3, u32(0), u32(0), u32(1), u32(0), u32(0), zeros(8), u16(0), u16(0), u16(0), u16(0),
    ...MATRIX.map(u32), u32(width * 0x10000), u32(height * 0x10000));
  const mvhd = fullBox('mvhd', 0, 0, u32(0), u32(0), u32(TIMESCALE), u32(0), u32(0x10000), u16(0x100), zeros(10),
    ...MATRIX.map(u32), zeros(24), u32(2));
  const mvex = box('mvex', fullBox('trex', 0, 0, u32(1), u32(1), u32(0), u32(0), u32(0)));
  return concat([box('ftyp', ascii('isom'), u32(0x200), ascii('isom'), ascii('iso6'), ascii('mp41')),
    box('moov', mvhd, box('trak', tkhd, mdia), mvex)]);
}

/** moof + mdat holding one sample. `decodeTime` and `duration` are in TIMESCALE units. */
export function mediaSegment({ sequence, decodeTime, duration, data, key }) {
  // sample_depends_on 2 (a sync sample) for key frames; depends_on 1 and is_non_sync for the rest.
  const flags = key ? 0x02000000 : 0x01010000;
  const moof = dataOffset => box('moof', fullBox('mfhd', 0, 0, u32(sequence)),
    box('traf', fullBox('tfhd', 0, 0x020000, u32(1)), fullBox('tfdt', 1, 0, u64(decodeTime)),
      fullBox('trun', 0, 0x000701, u32(1), u32(dataOffset), u32(duration), u32(data.length), u32(flags))));
  const header = moof(moof(0).length + 8);
  const out = new Uint8Array(header.length + 8 + data.length);
  out.set(header);
  out.set(u32(8 + data.length), header.length);
  out.set(ascii('mdat'), header.length + 4);
  out.set(data, header.length + 8);
  return out;
}

/** Annex-B (3- or 4-byte start codes) to 4-byte length prefixes, or null when there is no start code. */
export function annexBToLengthPrefixed(bytes) {
  const codes = [];
  for (let i = 0; i + 2 < bytes.length; i++) {
    if (bytes[i] === 0 && bytes[i + 1] === 0 && bytes[i + 2] === 1) {
      codes.push(i);
      i += 2;
    }
  }
  if (!codes.length) return null;
  const units = [];
  codes.forEach((code, index) => {
    let end = index + 1 < codes.length ? codes[index + 1] : bytes.length;
    while (end > code + 3 && bytes[end - 1] === 0) end--; // a NAL unit never ends with a zero byte
    if (end > code + 3) units.push(bytes.subarray(code + 3, end));
  });
  return concat(units.flatMap(unit => [u32(unit.length), unit]));
}
