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

test('the service worker answers only its own files, so the download pages in its scope reach the network', () => {
  const handlers = {};
  const worker = { location: new URL('https://example.test/DiPlay/sw.js'), addEventListener: (type, handler) => { handlers[type] = handler; } };
  const cache = { match: async () => new Response('cached') };
  new Function('self', 'caches', 'fetch', read('sw.js'))(worker, { open: async () => cache }, async () => new Response('network'));
  const answers = (url, method = 'GET') => {
    let answered = false;
    handlers.fetch({ request: { method, url }, respondWith: () => { answered = true; } });
    return answered;
  };
  for (const url of ['https://example.test/DiPlay/', 'https://example.test/DiPlay/?t=100.64.0.1', 'https://example.test/DiPlay/index.html',
    'https://example.test/DiPlay/app.js?v=2', 'https://example.test/DiPlay/decoder-worker.js', 'https://example.test/DiPlay/icon.svg',
    // The pairing link: a navigation's request URL keeps its fragment.
    'https://example.test/DiPlay/#c=123456', 'https://example.test/DiPlay/?t=100.64.0.1#c=123456']) {
    assert.ok(answers(url), url);
  }
  for (const url of ['https://example.test/DiPlay/download/', 'https://example.test/DiPlay/download/zh-Hans/',
    'https://example.test/DiPlay/download/assets/site.css', 'https://example.test/DiPlay/download/index.html',
    'https://example.test/DiPlay/download/#install', 'https://example.test/DiPlay/play/', 'https://example.test/DiPlay/play/#c=123456',
    'https://example.test/DiPlay/play/sw.js', 'https://example.test/DiPlay/sw.js',
    'https://example.test/DiPlay/app.js/', 'https://example.test/app.js', 'https://other.test/DiPlay/app.js',
    'http://100.109.220.253:8080/hello', 'http://100.109.220.253:8080/play/app.js']) {
    assert.ok(!answers(url), url);
  }
  assert.ok(!answers('https://example.test/DiPlay/', 'POST'));
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
