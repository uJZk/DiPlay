// SPDX-License-Identifier: GPL-3.0-only
// The published GitHub Pages site (scripts/assemble_site.py): the page at the root, the download pages in download/.
import test from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const SITE = join(ROOT, 'site');
const SCRIPT = /<script>([\s\S]*?)<\/script>/;

function assemble() {
  const parent = mkdtempSync(join(tmpdir(), 'tiplay-site-'));
  execFileSync('python3', [join(ROOT, 'scripts', 'assemble_site.py'), join(parent, 'site')], { stdio: 'pipe' });
  return { out: join(parent, 'site'), done: () => rmSync(parent, { recursive: true, force: true }) };
}

test('the page is the site root and the download pages are in download/', () => {
  const { out, done } = assemble();
  try {
    for (const name of readdirSync(join(SITE, 'play'))) {
      assert.deepEqual(readFileSync(join(out, name)), readFileSync(join(SITE, 'play', name)), name);
    }
    for (const name of ['index.html', 'zh-Hans/index.html', 'assets/site.css']) {
      assert.deepEqual(readFileSync(join(out, 'download', name)), readFileSync(join(SITE, name)), name);
    }
    assert.ok(!existsSync(join(out, 'download', 'play')), 'the page is published once');
  } finally {
    done();
  }
});

test('the former addresses forward: play/ to the root with ?t= and #c=, a language page to download/', () => {
  const { out, done } = assemble();
  try {
    const forward = SCRIPT.exec(readFileSync(join(out, 'play', 'index.html'), 'utf8'))[1];
    const from = new URL('https://ujzk.github.io/DiPlay/play/?t=100.64.0.1#c=123456');
    let target = null;
    new Function('location', forward)({ search: from.search, hash: from.hash, replace: url => { target = url; } });
    assert.equal(new URL(target, from).href, 'https://ujzk.github.io/DiPlay/?t=100.64.0.1#c=123456');

    const editions = readdirSync(SITE, { withFileTypes: true })
      .filter(entry => entry.isDirectory() && existsSync(join(SITE, entry.name, 'index.html')) && entry.name !== 'play')
      .map(entry => entry.name);
    assert.ok(editions.includes('zh-Hans'), editions.join());
    for (const edition of editions) {
      const html = readFileSync(join(out, edition, 'index.html'), 'utf8');
      const refresh = /<meta http-equiv="refresh" content="0; url=([^"]+)">/.exec(html)?.[1];
      assert.equal(new URL(refresh, `https://ujzk.github.io/DiPlay/${edition}/`).href, `https://ujzk.github.io/DiPlay/download/${edition}/`);
    }
  } finally {
    done();
  }
});

test('the worker at play/ unregisters itself and reloads its pages, which then reach the forwarder', async () => {
  const { out, done } = assemble();
  try {
    const handlers = {}, navigated = [];
    let unregistered = false, skipped = false, activated = null;
    const worker = {
      addEventListener: (type, handler) => { handlers[type] = handler; },
      skipWaiting: () => { skipped = true; },
      registration: { unregister: async () => { unregistered = true; return true; } },
      clients: { matchAll: async () => [{ url: 'https://ujzk.github.io/DiPlay/play/?t=100.64.0.1', navigate: async url => navigated.push(url) }] },
    };
    new Function('self', readFileSync(join(out, 'play', 'sw.js'), 'utf8'))(worker);
    assert.equal(handlers.fetch, undefined, 'it answers no requests');
    handlers.install();
    handlers.activate({ waitUntil: promise => { activated = promise; } });
    await activated;
    assert.ok(skipped && unregistered);
    assert.deepEqual(navigated, ['https://ujzk.github.io/DiPlay/play/?t=100.64.0.1']);
  } finally {
    done();
  }
});
