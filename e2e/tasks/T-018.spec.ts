import { test, expect } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

// The three APIs T-018 removes (05 section 2.2).
const REMOVED_PATHS = ['/api/llm-test', '/api/mcp-ask', '/api/mcp-status'] as const;
const EXPECTED_PORTS = ['messenger', 'agent'];

test('TC-014 T-018 제거한 API 3개가 404이고 연동 카드가 메신저·코딩 에이전트 2행이다', { tag: ['@task:T-018'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);

  // Removed endpoints answer 404 for GET and POST.
  for (const path of REMOVED_PATHS) {
    expect((await page.request.get(path)).status(), `GET ${path}`).toBe(404);
    expect((await page.request.post(path)).status(), `POST ${path}`).toBe(404);
  }
  // Kept: user-defined MCP list and system status.
  expect((await page.request.get('/api/mcp-servers')).status()).toBe(200);
  expect((await page.request.get('/api/system/status')).status()).toBe(200);

  // API shape: exactly messenger + agent, no chat/tool model fields.
  const platform = await (await page.request.get('/api/platform')).json();
  expect(platform.ports.map((p: { port: string }) => p.port)).toEqual(EXPECTED_PORTS);
  expect(Object.keys(platform)).not.toContain('chatModel');
  expect(Object.keys(platform)).not.toContain('toolChatModel');

  // Home: the integration status list has two rows.
  await page.goto('/home.html');
  await expect(page.getByRole('heading', { name: /연동 상태/ })).toBeVisible();
  await expect(page.locator('#homePorts .prow')).toHaveCount(2);
  await shot(page, testInfo, 'home');

  // Platform: the integration card has two rows, no chat model / tool model rows (final result screen).
  await page.goto('/platform.html');
  await expect(page.locator('#platPorts .plat')).toHaveCount(2);
  const card = await page.locator('#platPorts').innerText();
  expect(card).toMatch(/메신저/);
  expect(card).toMatch(/코딩 에이전트/);
  expect(card).not.toMatch(/채팅 모델|도구 모델|운영 MCP/);
  // Health of each port depends on the host (no Claude CLI login in QA), so only the denominator is fixed.
  await expect(page.locator('#platSummary')).toHaveText(/^(2개 모두 정상|\d+\/2 정상)$/);
  await shot(page, testInfo, 'platform-ports');

  expect(errors).toEqual([]);
});
