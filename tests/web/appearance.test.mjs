import test from 'node:test';
import assert from 'node:assert/strict';
import { followAppearance } from '../../site/play/appearance.js';

test('theme changes and each reconnection report the current browser theme', () => {
  let changed, outbox = null;
  const sent = [];
  const media = { matches: false, addEventListener: (type, listener) => { assert.equal(type, 'change'); changed = listener; } };
  const report = followAppearance(media, () => outbox);
  report(); // Disconnected: no control messages.
  assert.equal(sent.length, 0);
  outbox = { push: event => sent.push(event) };
  report();
  media.matches = true;
  changed();
  outbox = null;
  changed();
  outbox = { push: event => sent.push(event) };
  report();
  assert.deepEqual(sent.map(event => event.v.themeDark), [false, true, true]);
});

test('older browser matchMedia listener is supported', () => {
  let listener;
  const sent = [];
  followAppearance({ matches: true, addListener: value => { listener = value; } }, () => ({ push: event => sent.push(event) }));
  listener();
  assert.deepEqual(sent, [{ k: 'st', v: { themeDark: true } }]);
});
