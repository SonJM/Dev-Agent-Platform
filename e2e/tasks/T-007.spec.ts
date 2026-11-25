import { test, expect, type Page } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

// The eight APIs T-007 removes (05 section 2.2, task order scope item 1).
const REMOVED_PATHS = [
  '/api/rooms',
  '/api/rooms-config',
  '/api/recent',
  '/api/docs-status',
  '/api/docs-refresh',
  '/api/docs-search',
  '/api/docs-read',
  '/api/status',
] as const;
const EXPECTED_KINDS = ['issue', 'research', 'system'];

async function bodyText(page: Page): Promise<string> {
  return page.locator('body').innerText();
}

test('TC-014 T-007 제거한 API 8개가 404이고 서버 응답 기반 화면 행이 사라졌다', { tag: ['@task:T-007'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const failedRequests: string[] = [];
  page.on('response', (res) => {
    if (res.status() >= 400 && !res.url().includes('favicon')) failedRequests.push(`${res.status()} ${new URL(res.url()).pathname}`);
  });

  // Removed endpoints answer 404 (GET; the POST-only ones would answer 405 if a mapping were left).
  for (const path of REMOVED_PATHS) {
    const res = await page.request.get(path);
    expect(res.status(), `GET ${path}`).toBe(404);
  }
  // A removed notification kind is refused with the existing "unknown kind" answer.
  for (const key of ['summary', 'mention']) {
    const res = await page.request.post(`/api/messenger-notify?key=${key}&on=true`);
    expect((await res.json()).ok, `POST kind ${key}`).toBe(false);
  }
  // System status stays up.
  expect((await page.request.get('/api/system/status')).status()).toBe(200);
  failedRequests.length = 0; // the 404 probes above are expected; only screen-driven failures count below

  // API shape: exactly three kinds, no knowledge port.
  const notify = await (await page.request.get('/api/messenger-notify')).json();
  expect(notify.kinds.map((k: { key: string }) => k.key)).toEqual(EXPECTED_KINDS);
  const platform = await (await page.request.get('/api/platform')).json();
  expect(Object.keys(platform.ports)).not.toContain('knowledge');

  // Home: KPI sub text no longer mentions the knowledge (RAG) port.
  await page.goto('/home.html');
  await expect(page.getByRole('heading', { name: /연동 상태/ })).toBeVisible();
  await expect(page.getByRole('link', { name: /^연동 \d+ \/ \d+ 정상/ })).toBeVisible();
  expect(await bodyText(page)).not.toMatch(/지식|RAG/);
  await shot(page, testInfo, 'home');

  // Platform: the integration card has no knowledge (RAG) row and the setup check has none either.
  await page.goto('/platform.html');
  await expect(page.getByRole('heading', { name: 'MCP 서버' })).toBeVisible();
  await expect(page.getByText('방 목록 · 메시지 수집')).toHaveCount(0);
  await expect(page.locator('body')).not.toContainText(/지식|RAG/);
  await shot(page, testInfo, 'platform');

  // Messenger: the notification card lists exactly issue / research / system rows (final result screen).
  await page.goto('/messenger.html');
  await expect(page.getByRole('heading', { name: /메신저 알림/ })).toBeVisible();
  const rows = page.locator('#notifyKinds .nrow');
  await expect(rows).toHaveCount(3);
  const keys = await page.locator('#notifyKinds input[data-notify]').evaluateAll((els) => els.map((e) => (e as HTMLElement).dataset.notify));
  expect(keys).toEqual(EXPECTED_KINDS);
  const card = await page.locator('section.card', { has: page.getByRole('heading', { name: /메신저 알림/ }) }).innerText();
  expect(card).not.toMatch(/방 요약|멘션 초안|최근 활동|멘션 처리/);
  expect(await bodyText(page)).not.toMatch(/방 설정|수집 메시지|지식/);
  await shot(page, testInfo, 'messenger-notify');

  expect(failedRequests).toEqual([]);
  expect(errors).toEqual([]);
});
