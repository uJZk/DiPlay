/*! SPDX-License-Identifier: GPL-3.0-only
 * TiPlay page cache: once loaded, the page opens without internet access. The worker answers only requests for the
 * page's own files in ASSETS, with any query or fragment (the pairing link's #c=), from the cache. Its scope is the
 * page's directory, which is the root of the published site, so every other page there (the download pages under
 * download/) goes to the network untouched.
 * Requests to the phone (cross-origin, plain HTTP) are never answered here: a fetch re-issued from the worker might
 * not get the page's local-network and mixed-content treatment.
 *
 * Bump VERSION whenever a file in ASSETS changes; the new worker caches the new files and deletes older caches. */
const VERSION = 'v2';
const CACHE = `tiplay-play-${VERSION}`;
const ASSETS = ['./', 'index.html', 'style.css', 'app.js', 'link.js', 'video.js', 'decoder-worker.js', 'mse.js', 'fmp4.js', 'icon.svg'];
const OWN = new Set(ASSETS.map(path => new URL(path, self.location).href));

self.addEventListener('install', event => {
  // Bypass the HTTP cache (GitHub Pages sends max-age=600) so a new version never stores older files.
  const requests = ASSETS.map(path => new Request(path, { cache: 'reload' }));
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(requests)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', event => {
  event.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(key => key.startsWith('tiplay-play-') && key !== CACHE).map(key => caches.delete(key))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', event => {
  const { request } = event;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  url.search = url.hash = '';
  if (!OWN.has(url.href)) return;
  event.respondWith(caches.open(CACHE)
    .then(cache => cache.match(request, { ignoreSearch: true }))
    .then(cached => cached ?? fetch(request)));
});
