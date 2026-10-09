// SPDX-License-Identifier: GPL-3.0-only
// The MSE fallback's fragmented MP4 muxer.
import test from 'node:test';
import assert from 'node:assert/strict';
import { TIMESCALE, annexBToLengthPrefixed, initSegment, mediaSegment, mseType } from '../../site/play/fmp4.js';

/** Box tree: [{type, start, end, children}] for the container boxes this muxer writes. */
function boxes(bytes, start = 0, end = bytes.length) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const containers = { moov: 8, trak: 8, mdia: 8, minf: 8, stbl: 8, dinf: 8, mvex: 8, moof: 8, traf: 8, stsd: 16, avc1: 86, hvc1: 86, vp09: 86 };
  const out = [];
  for (let offset = start; offset < end;) {
    const size = view.getUint32(offset), type = String.fromCharCode(...bytes.subarray(offset + 4, offset + 8));
    assert.ok(size >= 8 && offset + size <= end, `${type} fits its parent`);
    const box = { type, start: offset, end: offset + size };
    if (type in containers) box.children = boxes(bytes, offset + containers[type], offset + size);
    out.push(box);
    offset += size;
  }
  return out;
}
const find = (tree, ...path) => path.reduce((nodes, type) => nodes.find(node => node.type === type)?.children ?? nodes.find(node => node.type === type), tree);
const u32 = (bytes, offset) => new DataView(bytes.buffer, bytes.byteOffset).getUint32(offset);

const avcC = Uint8Array.of(1, 0x64, 0, 0x28, 0xff, 0xe1, 0, 4, 0x67, 0x64, 0, 0x28, 1, 0, 2, 0x68, 0xee);

test('the H.264 init segment nests one video track with the avcC record', () => {
  const init = initSegment({ codec: 'avc1.640028', width: 1182, height: 920, record: avcC });
  const tree = boxes(init);
  assert.deepEqual(tree.map(box => box.type), ['ftyp', 'moov']);
  assert.deepEqual(find(tree, 'moov').map(box => box.type), ['mvhd', 'trak', 'mvex']);
  const mdhd = find(tree, 'moov', 'trak', 'mdia').find(box => box.type === 'mdhd');
  assert.equal(u32(init, mdhd.start + 20), TIMESCALE);
  const tkhd = find(tree, 'moov', 'trak').find(box => box.type === 'tkhd');
  assert.deepEqual([u32(init, tkhd.end - 8) / 65536, u32(init, tkhd.end - 4) / 65536], [1182, 920]);
  const entry = find(tree, 'moov', 'trak', 'mdia', 'minf', 'stbl', 'stsd').find(box => box.type === 'avc1');
  assert.deepEqual([init[entry.start + 32] << 8 | init[entry.start + 33], init[entry.start + 34] << 8 | init[entry.start + 35]], [1182, 920]);
  const config = entry.children.find(box => box.type === 'avcC');
  assert.deepEqual(init.subarray(config.start + 8, config.end), avcC);
  assert.equal(mseType('avc1.640028'), 'video/mp4; codecs="avc1.640028"');
});

test('HEVC and VP9 get their own sample entries; other codecs are refused', () => {
  const hevc = boxes(initSegment({ codec: 'hvc1.1.6.L120.90', width: 800, height: 480, record: Uint8Array.of(1, 2, 3) }));
  assert.ok(find(hevc, 'moov', 'trak', 'mdia', 'minf', 'stbl', 'stsd').find(box => box.type === 'hvc1').children.find(box => box.type === 'hvcC'));
  const vp9 = initSegment({ codec: 'vp09.00.10.08', width: 800, height: 480 });
  const vpcC = find(boxes(vp9), 'moov', 'trak', 'mdia', 'minf', 'stbl', 'stsd').find(box => box.type === 'vp09').children[0];
  assert.equal(vpcC.type, 'vpcC');
  assert.deepEqual(Array.from(vp9.subarray(vpcC.start + 8, vpcC.end)), [1, 0, 0, 0, 0, 10, 0x82, 1, 1, 1, 0, 0]);
  assert.equal(initSegment({ codec: 'vp8', width: 800, height: 480 }), null);
  assert.equal(initSegment({ codec: 'avc1.640028', width: 800, height: 480, record: new Uint8Array(0) }), null);
});

test('a media segment points its sample at the mdat payload', () => {
  const data = Uint8Array.of(0, 0, 0, 2, 0x65, 0x88);
  const segment = mediaSegment({ sequence: 7, decodeTime: 2 ** 33 + 5, duration: 1500, data, key: true });
  const tree = boxes(segment);
  assert.deepEqual(tree.map(box => box.type), ['moof', 'mdat']);
  const traf = find(tree, 'moof', 'traf');
  const tfdt = traf.find(box => box.type === 'tfdt'), trun = traf.find(box => box.type === 'trun');
  assert.equal(segment[tfdt.start + 8], 1, 'tfdt version 1 carries 64-bit times');
  assert.equal(u32(segment, tfdt.start + 12) * 2 ** 32 + u32(segment, tfdt.start + 16), 2 ** 33 + 5);
  const dataOffset = u32(segment, trun.start + 16);
  assert.deepEqual(segment.subarray(dataOffset, dataOffset + data.length), data);
  assert.deepEqual([u32(segment, trun.start + 20), u32(segment, trun.start + 24), u32(segment, trun.start + 28)], [1500, 6, 0x02000000]);
  const delta = mediaSegment({ sequence: 8, decodeTime: 0, duration: 1500, data, key: false });
  assert.equal(u32(delta, find(boxes(delta), 'moof', 'traf').find(box => box.type === 'trun').start + 28), 0x01010000);
});

test('Annex-B access units become 4-byte length-prefixed NAL units', () => {
  const annexB = Uint8Array.of(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5, 6);
  assert.deepEqual(Array.from(annexBToLengthPrefixed(annexB)),
    [0, 0, 0, 3, 0x67, 1, 2, 0, 0, 0, 2, 0x68, 3, 0, 0, 0, 4, 0x65, 4, 5, 6]);
  assert.equal(annexBToLengthPrefixed(Uint8Array.of(0x65, 1, 2)), null);
});
