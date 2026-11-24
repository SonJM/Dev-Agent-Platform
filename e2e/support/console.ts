import type { Page } from '@playwright/test';

// Collects browser console errors and uncaught page errors (frames included).
// favicon requests are ignored: the app ships no favicon.
export function collectConsoleErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const location = msg.location().url ?? '';
    if (location.includes('favicon')) return;
    errors.push(`console: ${msg.text()} @ ${location}`);
  });
  page.on('pageerror', (error) => errors.push(`pageerror: ${error.message}`));
  return errors;
}
