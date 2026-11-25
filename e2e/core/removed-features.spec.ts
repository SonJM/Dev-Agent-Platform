import { test, expect, type Page } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

// Paths removed by FR-207 (05 section 2.2) plus the status endpoints the screens must stop calling.
const REMOVED_API =
  /\/api\/(status|rooms|rooms-config|recent|docs-status|docs-refresh|docs-search|preview|llm-test|mcp-ask|mcp-status)(\?|\/|$)|\/shots(\?|$)/;
const SCREENS = ['/home.html', '/messenger.html', '/platform.html', '/agent.html'] as const;

// TC-014 part: APIs removed by T-007 (GET answers 404) and by T-018 (GET and POST answer 404).
const REMOVED_PATHS_T007 = [
  '/api/rooms',
  '/api/rooms-config',
  '/api/recent',
  '/api/docs-status',
  '/api/docs-refresh',
  '/api/docs-search',
  '/api/docs-read',
  '/api/status',
] as const;
const REMOVED_PATHS_T018 = ['/api/llm-test', '/api/mcp-ask', '/api/mcp-status'] as const;
const EXPECTED_KINDS = ['issue', 'research', 'system'];
const EXPECTED_PORTS = ['messenger', 'agent'];

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

// TC-014 part: removed endpoints answer 404, removed notification kinds are refused, kept APIs stay up.
async function expectRemovedApis(page: Page): Promise<void> {
  for (const path of REMOVED_PATHS_T007) {
    // GET only; a POST-only mapping left behind would answer 405 here.
    expect((await page.request.get(path)).status(), `GET ${path}`).toBe(404);
  }
  for (const key of ['summary', 'mention']) {
    const res = await page.request.post(`/api/messenger-notify?key=${key}&on=true`);
    expect((await res.json()).ok, `POST kind ${key}`).toBe(false);
  }
  for (const path of REMOVED_PATHS_T018) {
    expect((await page.request.get(path)).status(), `GET ${path}`).toBe(404);
    expect((await page.request.post(path)).status(), `POST ${path}`).toBe(404);
  }
  // Kept: system status and the user-defined MCP list.
  expect((await page.request.get('/api/system/status')).status()).toBe(200);
  expect((await page.request.get('/api/mcp-servers')).status()).toBe(200);

  // API shape: exactly three notification kinds, no knowledge port, messenger + agent ports, no chat/tool model.
  const notify = await (await page.request.get('/api/messenger-notify')).json();
  expect(notify.kinds.map((k: { key: string }) => k.key)).toEqual(EXPECTED_KINDS);
  const platform = await (await page.request.get('/api/platform')).json();
  expect(Object.keys(platform.ports)).not.toContain('knowledge');
  expect(platform.ports.map((p: { port: string }) => p.port)).toEqual(EXPECTED_PORTS);
  expect(Object.keys(platform)).not.toContain('chatModel');
  expect(Object.keys(platform)).not.toContain('toolChatModel');
}

test('TC-013 제거 대상 요소·API가 되살아나지 않고 남은 화면이 정상 동작한다 (+ TC-014 제거 API 404)', { tag: ['@core'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const req = watchRequests(page);

  // TC-014 part: API probes first (APIRequestContext calls raise no page events, so the watcher stays clean).
  await expectRemovedApis(page);

  // Home: no messenger statistics card, KPI sub text changed, no knowledge (RAG) wording, two integration rows.
  await page.goto('/home.html');
  await expect(page.getByRole('heading', { name: /연동 상태/ })).toBeVisible();
  await expect(page.locator('#homeMessenger')).toHaveCount(0);
  // The KPI sub text shows the port names only while some port needs attention; with no messenger
  // configured the messenger label is listed, and the old 'LLM · 지식' wording must never appear.
  await expect(page.getByRole('link', { name: /^연동 \d+ \/ \d+ 정상/ })).toBeVisible();
  const home = await bodyText(page);
  expect(home).not.toMatch(/수집 메시지|메신저 · LLM · 지식/);
  expect(home).not.toMatch(/지식|RAG/);
  await expect(page.locator('#homePorts .prow')).toHaveCount(2);
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
  // The notification card is checked for the removed kind descriptions (including 「최근 활동」) below;
  // the body check leaves 「최근 활동」 out as before.
  expect(msgr).not.toMatch(/방 설정|수집 메시지|수집 모드|멘션 모드|LLM 헬스체크/);
  expect(msgr).not.toMatch(/방 설정|수집 메시지|지식/);
  // Changed command guide wording (issue status / stop rows and the last sentence).
  await expect(page.locator('code', { hasText: '이슈 상태' })).toBeVisible();
  await expect(page.locator('code', { hasText: '이슈 중단 [repo#12]' })).toBeVisible();
  await expect(page.getByText('세션·큐 현황을 보거나 작업 중인 이슈를 멈춥니다.')).toBeVisible();
  await expect(
    page.getByText('그 밖의 입력은 무시합니다. 기다리는 이슈가 없으면 명령 안내를, 있으면 답하는 방법을 단계마다 한 번만 보내 줍니다.'),
  ).toBeVisible();
  await expect(page.getByText('메신저에서 직접 보낸 명령의 답')).toBeVisible();
  // The command card lists neither ops queries nor room summaries.
  const commandCard = await page.locator('section.card', { has: page.getByRole('heading', { name: /메신저 명령/ }) }).innerText();
  expect(commandCard).not.toMatch(/운영|방 요약|일반 대화/);
  // The notification card lists exactly issue / research / system rows.
  const rows = page.locator('#notifyKinds .nrow');
  await expect(rows).toHaveCount(3);
  const keys = await page.locator('#notifyKinds input[data-notify]').evaluateAll((els) => els.map((e) => (e as HTMLElement).dataset.notify));
  expect(keys).toEqual(EXPECTED_KINDS);
  const notifyCard = await page.locator('section.card', { has: page.getByRole('heading', { name: /메신저 알림/ }) }).innerText();
  expect(notifyCard).not.toMatch(/방 요약|멘션 초안|최근 활동|멘션 처리/);
  await shot(page, testInfo, 'messenger');

  // Platform: no RAG card, no built-in ops MCP status/query, no preview item, no knowledge row;
  // integration card has two rows; custom MCP form opens.
  await page.goto('/platform.html');
  await expect(page.getByRole('heading', { name: 'MCP 서버' })).toBeVisible();
  const ids = ['#mcpServers', '#mcpSummary', '#mcpQ', '#mcpAskBtn', '#mcpAnswer', '#previewStat'];
  for (const id of ids) await expect(page.locator(id)).toHaveCount(0);
  const plat = await bodyText(page);
  // Static cards: no RAG card heading and an MCP card without the ops-MCP wording. Rows drawn from the
  // preview setup check are T-009's concern and are not asserted here.
  await expect(page.getByRole('heading', { name: /사내 문서|RAG/ })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: /운영 MCP/ })).toHaveCount(0);
  const mcpCard = await page.locator('section.card', { has: page.getByRole('heading', { name: 'MCP 서버' }) }).innerText();
  expect(mcpCard).not.toMatch(/@AI 운영|운영 MCP|운영 질의/);
  expect(plat).not.toMatch(/프리뷰 스크린샷/);
  await expect(page.getByText('방 목록 · 메시지 수집')).toHaveCount(0);
  await expect(page.locator('body')).not.toContainText(/지식|RAG/);
  // Integration card: messenger + coding agent rows only. Health of each port depends on the host
  // (no Claude CLI login in QA), so only the denominator is fixed.
  await expect(page.locator('#platPorts .plat')).toHaveCount(2);
  const portCard = await page.locator('#platPorts').innerText();
  expect(portCard).toMatch(/메신저/);
  expect(portCard).toMatch(/코딩 에이전트/);
  expect(portCard).not.toMatch(/채팅 모델|도구 모델|운영 MCP/);
  await expect(page.locator('#platSummary')).toHaveText(/^(2개 모두 정상|\d+\/2 정상)$/);
  await shot(page, testInfo, 'platform-ports');
  await page.getByRole('button', { name: '+ 서버 추가' }).click();
  await expect(page.locator('#mfName')).toBeVisible();
  await shot(page, testInfo, 'platform-mcp-form');

  // Agent: no screenshot gallery (final result screen).
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
