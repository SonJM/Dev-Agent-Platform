import { test, type Page, type TestInfo } from '@playwright/test';

const counters = new WeakMap<TestInfo, number>();

// Takes a numbered full-page screenshot for the QA report: 01-<name>.png, 02-<name>.png, ...
// The last shot of a test must be the final result screen.
export async function shot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const next = (counters.get(testInfo) ?? 0) + 1;
  counters.set(testInfo, next);
  const file = `${String(next).padStart(2, '0')}-${name}.png`;
  await test.step(`shot ${file}`, async () => {
    await page.screenshot({ path: testInfo.outputPath(file), fullPage: true });
  });
}
