import { test, expect, type Page } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

// Paths removed by FR-207 (05 section 2.2) plus the status endpoints the screens must stop calling.
const REMOVED_API =
  /\/api\/(status|rooms|rooms-config|recent|docs-status|docs-refresh|docs-search|preview|llm-test|mcp-ask|mcp-status)(\?|\/|$)|\/shots(\?|$)/;
const SCREENS = ['/home.html', '/messenger.html', '/platform.html', '/agent.html'] as const;

// Records requests to removed paths and every failed request (network failure or status >= 400).
function watchRequests(page: Page) {
  const removed: string[] = [];
  const failed: string[] = [];
  page.on('request', (req) => {
    const path = new URL(req.url()).pathname + new URL(req.url()).search;
    if (REMOVED_API.test(path)) removed.push(`${req.method()} ${path}`);
  });
  page.on('requestfailed', (req) => failed.push(`${req.method()} ${new URL(req.url()).pathname} ${req.failure()?.errorText}`));
  page.on('response', (res) => {
    if (res.status() >= 400 && !res.url().includes('favicon')) failed.push(`${res.status()} ${new URL(res.url()).pathname}`);
  });
  return { removed, failed };
}

async function bodyText(page: Page): Promise<string> {
  return page.locator('body').innerText();
}

test('TC-013 T-008 화면 정리 — 제거 대상 요소가 없고 남은 화면이 정상 동작한다', { tag: ['@task:T-008'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const req = watchRequests(page);

  // Home: no messenger statistics card, KPI sub text changed.
  await page.goto('/home.html');
  await expect(page.getByRole('heading', { name: /연동 상태/ })).toBeVisible();
  await expect(page.locator('#homeMessenger')).toHaveCount(0);
  // The KPI sub text shows the port names only while some port needs attention; with no messenger
  // configured the messenger label is listed, and the old 'LLM · 지식' wording must never appear.
  await expect(page.getByRole('link', { name: /^연동 \d+ \/ \d+ 정상/ })).toBeVisible();
  const home = await bodyText(page);
  expect(home).not.toMatch(/수집 메시지|메신저 · LLM · 지식/);
  await shot(page, testInfo, 'home');

  // Messenger: connection state, notification card and command guide; no stats/rooms/recent.
  await page.goto('/messenger.html');
  await expect(page.getByRole('heading', { name: /연결 상태/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: /메신저 알림/ })).toBeVisible();
  await expect(page.getByRole('heading', { name: /메신저 명령/ })).toBeVisible();
  await expect(page.locator('#msgrState')).not.toContainText(/불러오는 중/);
  await expect(page.locator('#msgrState .skel')).toHaveCount(0);
  await expect(page.locator('#stats')).toHaveCount(0);
  await expect(page.getByRole('heading', { name: /방 설정|최근 활동/ })).toHaveCount(0);
  const msgr = await bodyText(page);
  // 「최근 활동」 is checked as a heading above: the server-drawn summary-kind description (removed
  // by T-007) still mentions it in plain text, so the body check leaves it out.
  expect(msgr).not.toMatch(/방 설정|수집 메시지|수집 모드|멘션 모드|LLM 헬스체크/);
  // Changed command guide wording (issue status / stop rows and the last sentence).
  await expect(page.locator('code', { hasText: '이슈 상태' })).toBeVisible();
  await expect(page.locator('code', { hasText: '이슈 중단 [repo#12]' })).toBeVisible();
  await expect(page.getByText('세션·큐 현황을 보거나 작업 중인 이슈를 멈춥니다.')).toBeVisible();
  await expect(
    page.getByText('그 밖의 입력은 무시합니다. 기다리는 이슈가 없으면 명령 안내를, 있으면 답하는 방법을 단계마다 한 번만 보내 줍니다.'),
  ).toBeVisible();
  await expect(page.getByText('메신저에서 직접 보낸 명령의 답')).toBeVisible();
  // The command card lists neither ops queries nor room summaries (the notification card's
  // server-drawn kind list is T-007's concern, so only the command card is checked).
  const commandCard = await page.locator('section.card', { has: page.getByRole('heading', { name: /메신저 명령/ }) }).innerText();
  expect(commandCard).not.toMatch(/운영|방 요약|일반 대화/);
  await shot(page, testInfo, 'messenger');

  // Platform: no RAG card, no built-in ops MCP status/query, no preview item; custom MCP form opens.
  await page.goto('/platform.html');
  await expect(page.getByRole('heading', { name: 'MCP 서버' })).toBeVisible();
  const ids = ['#mcpServers', '#mcpSummary', '#mcpQ', '#mcpAskBtn', '#mcpAnswer', '#previewStat'];
  for (const id of ids) await expect(page.locator(id)).toHaveCount(0);
  const plat = await bodyText(page);
  // Rows drawn from server responses (setup check, integration card) still name the preview, the
  // docs site and ops queries until T-007/T-009 change the backend, so only the static cards are
  // checked: no RAG card heading and an MCP card without the ops-MCP wording.
  await expect(page.getByRole('heading', { name: /사내 문서|RAG/ })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: /운영 MCP/ })).toHaveCount(0);
  const mcpCard = await page.locator('section.card', { has: page.getByRole('heading', { name: 'MCP 서버' }) }).innerText();
  expect(mcpCard).not.toMatch(/@AI 운영|운영 MCP|운영 질의/);
  expect(plat).not.toMatch(/프리뷰 스크린샷/);
  await page.getByRole('button', { name: '+ 서버 추가' }).click();
  await expect(page.locator('#mfName')).toBeVisible();
  await shot(page, testInfo, 'platform-mcp-form');

  // Agent: no screenshot gallery.
  await page.goto('/agent.html');
  await expect(page.getByText('세션을 선택하세요')).toBeVisible();
  await expect(page.locator('#shots')).toHaveCount(0);
  expect(await bodyText(page)).not.toMatch(/프리뷰 스크린샷|프리뷰 열기/);
  await shot(page, testInfo, 'agent');

  // Every screen was loaded without calls to removed paths, failed requests or console errors.
  expect(SCREENS.length).toBe(4);
  expect(req.removed).toEqual([]);
  expect(req.failed).toEqual([]);
  expect(errors).toEqual([]);
});
