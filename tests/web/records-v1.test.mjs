// SPDX-License-Identifier: GPL-3.0-only
// The page must read the golden stream that the phone-side WebVideoRecordsTest writes.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { HEADER_BYTES, RECORD, RecordParser } from '../../site/play/link.js';
import { annexBToLengthPrefixed, initSegment } from '../../site/play/fmp4.js';

const fixtures = new URL('../../shared/src/test/resources/web/', import.meta.url);
const binary = new URL('records-v1.bin', fixtures), expectation = new URL('records-v1.json', fixtures);
const missing = [binary, expectation].filter(file => !existsSync(file)).map(file => file.pathname);
const skip = missing.length ? `fixture missing: ${missing.join(', ')}` : false;
const load = () => ({ bytes: new Uint8Array(readFileSync(binary)), golden: JSON.parse(readFileSync(expectation, 'utf8')) });
const base64 = bytes => Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength).toString('base64');
const fields = ({ payloadLength, kind, flags, codec, version, sequence, epoch, timestampUs, payload }) =>
  ({ payloadLength, kind, flags, codec, version, sequence, epoch, timestampUs, payload: base64(payload) });

test('golden records-v1.bin parses to records-v1.json', { skip }, () => {
  const { bytes, golden } = load();
  assert.equal(golden.headerSize, HEADER_BYTES);
  const expected = golden.records.map(({ name, offset, ...record }) => record);
  assert.deepEqual(new RecordParser().push(bytes).map(fields), expected);
  const parser = new RecordParser();
  const byteByByte = [];
  for (let i = 0; i < bytes.length; i++) byteByByte.push(...parser.push(bytes.subarray(i, i + 1)));
  assert.deepEqual(byteByByte.map(fields), expected);
  let offset = 0;
  for (const record of golden.records) {
    assert.equal(record.offset, offset, record.name);
    offset += HEADER_BYTES + record.payloadLength;
  }
  assert.equal(offset, bytes.length);
});

test('the golden config and key frame fit the MSE fallback', { skip }, () => {
  const [config, key] = new RecordParser().push(load().bytes);
  assert.equal(config.kind, RECORD.CONFIG);
  const info = JSON.parse(new TextDecoder().decode(config.payload));
  assert.match(info.codec, /^(avc1|hvc1)\./);
  const record = Buffer.from(info.record, 'base64');
  const init = initSegment({ ...info, record });
  assert.ok(Buffer.from(init).includes(Buffer.concat([Buffer.from('avcC'), record])), 'the avcC box carries the record');
  const units = annexBToLengthPrefixed(key.payload);
  const types = [];
  for (let i = 0; i < units.length; i += 4 + new DataView(units.buffer).getUint32(i)) types.push(units[i + 4] & 0x1f);
  assert.deepEqual(types, [7, 8, 5], 'SPS, PPS and the IDR slice');
});
