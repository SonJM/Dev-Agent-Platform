import { test, type Page, type TestInfo } from '@playwright/test';

const counters = new WeakMap<TestInfo, number>();

// Replaces local account paths (C:\Users\<account>, C:/Users/<account>) shown in the DOM with "~"
// so screenshots never carry the account name. Runs in every frame (the shell embeds tabs).
const LOCAL_PATH = /[A-Za-z]:[\\/]+Users[\\/]+[^\\/\s"'<>|]+/g;

async function maskLocalPaths(page: Page): Promise<void> {
  for (const frame of page.frames()) {
    await frame
      .evaluate((src) => {
        const re = new RegExp(src, 'g');
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        for (let n = walker.nextNode(); n; n = walker.nextNode()) {
          if (n.nodeValue && re.test(n.nodeValue)) n.nodeValue = n.nodeValue.replace(re, '~');
          re.lastIndex = 0;
        }
        document.querySelectorAll('[title]').forEach((el) => {
          const t = el.getAttribute('title') ?? '';
          if (re.test(t)) el.setAttribute('title', t.replace(re, '~'));
          re.lastIndex = 0;
        });
      }, LOCAL_PATH.source)
      .catch(() => undefined);
  }
}

// Takes a numbered full-page screenshot for the QA report: 01-<name>.png, 02-<name>.png, ...
// The last shot of a test must be the final result screen.
export async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const next = (counters.get(testInfo) ?? 0) + 1;
  counters.set(testInfo, next);
  const file = `${String(next).padStart(2, '0')}-${name}.png`;
  await test.step(`shot ${file}`, async () => {
    await maskLocalPaths(page);
    await page.screenshot({ path: testInfo.outputPath(file), fullPage: true });
  });
}
