#!/usr/bin/env python3
"""Assemble the published GitHub Pages site in one directory; no runtime dependencies.

    python3 scripts/assemble_site.py [OUT]     (default: _site)

- The site root holds the files of site/play/, the TiPlay page, so the car's link is just the site's address.
- download/ holds the rest of site/: the download pages that scripts/build_site.py generates, with their assets.
- play/ holds a forwarder for links to the page's former address: play/index.html opens the site root with the same
  query and fragment, and play/sw.js retires the Service Worker that the page registered there.

site/ stays the only copy of these files in git. Run scripts/build_site.py first.
"""
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / 'site'
PAGE = SITE / 'play'
DOWNLOAD = 'download'
FORMER = 'play'

FORWARD_PAGE = '''<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><meta name="robots" content="noindex"><title>TiPlay</title>
<script>location.replace('../' + location.search + location.hash);</script>
</head><body><p>TiPlay moved: <a href="../">open TiPlay</a>.</p></body></html>
'''

# The worker that play/ registered serves its cached copy of the page. The browser checks play/sw.js for updates on
# each visit; this version replaces that worker, unregisters it and reloads its pages, which then reach the forwarder.
RETIRE_WORKER = '''// TiPlay's page moved from play/ to the site root. This worker replaces the one that play/ registered: it
// unregisters itself and reloads its pages, so they reach play/index.html, which forwards to the site root.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', event => event.waitUntil(self.registration.unregister()
  .then(() => self.clients.matchAll({ type: 'window' }))
  .then(clients => Promise.all(clients.map(client => client.navigate(client.url).catch(() => {}))))));
'''


def assemble(out: Path) -> None:
    out = out.resolve()
    if out == ROOT or out in ROOT.parents or out == SITE or SITE in out.parents or out in SITE.parents:
        raise SystemExit(f'Refusing to assemble into {out}: choose a directory outside site/ and not above the repository')
    page = sorted(path.name for path in PAGE.iterdir())
    clashes = {DOWNLOAD, FORMER} & set(page)
    if clashes:
        raise SystemExit(f'site/play/ must not contain {", ".join(sorted(clashes))}: the published site uses those names')
    if not (SITE / 'index.html').is_file():
        raise SystemExit('site/index.html is missing: run scripts/build_site.py first')

    if out.exists():
        if any(out.iterdir()) and not (out / DOWNLOAD).is_dir():
            raise SystemExit(f'Refusing to replace {out}: it is not empty and not an assembled site')
        shutil.rmtree(out)
    shutil.copytree(PAGE, out)
    shutil.copytree(SITE, out / DOWNLOAD, ignore=lambda folder, names: [PAGE.name] if Path(folder) == SITE else [])
    (out / FORMER).mkdir()
    (out / FORMER / 'index.html').write_text(FORWARD_PAGE)
    (out / FORMER / 'sw.js').write_text(RETIRE_WORKER)
    print(f'Assembled {out}: the page ({len(page)} files) at the root, the download pages in {DOWNLOAD}/, '
          f'a forwarder in {FORMER}/')


if __name__ == '__main__':
    assemble(Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / '_site')
