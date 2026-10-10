/*! SPDX-License-Identifier: AGPL-3.0-only */
/** Report the browser theme over the existing authenticated control outbox. */
export function followAppearance(media, currentOutbox) {
  const report = () => currentOutbox()?.push({ k: 'st', v: { themeDark: media.matches } }, 'latest');
  if (media.addEventListener) media.addEventListener('change', report);
  else media.addListener(report);
  return report;
}
