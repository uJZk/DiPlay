// SPDX-License-Identifier: GPL-3.0-only
// The page must work from any path and offline once cached.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';

const play = new URL('../../site/play/', import.meta.url);
const read = name => readFileSync(new URL(name, play), 'utf8');

test('the service worker precaches every page file', () => {
  const assets = JSON.parse(/const ASSETS = (\[[^\]]*\]);/.exec(read('sw.js'))[1].replaceAll("'", '"'));
  const files = readdirSync(play).filter(name => name !== 'sw.js');
  assert.deepEqual([...assets].filter(name => name !== './').sort(), files.sort());
});

test('the page loads only relative, same-origin resources', () => {
  const html = read('index.html');
  for (const [, url] of html.matchAll(/(?:src|href)="([^"]+)"/g)) {
    if (url === 'https://github.com/uJZk/DiPlay') continue; // the GPL source link
    assert.match(url, /^[a-z][\w.-]*$/, url);
  }
  for (const name of ['app.js', 'decoder-worker.js', 'style.css']) {
    assert.doesNotMatch(read(name), /(?:import|from|url\()\s*['"(]?(?:https?:)?\/\//, name);
  }
});
