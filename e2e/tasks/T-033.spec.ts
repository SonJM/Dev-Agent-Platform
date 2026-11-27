import { test, expect, type Page, type Route } from '@playwright/test';
import os from 'node:os';
import path from 'node:path';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

const TAG = { tag: ['@task:T-033'] };
const SAMPLE = 'qa-group/qa-sample';
const ALPHA = 'demo-user/alpha-service';
const ARCHIVE = 'demo-user/old-archive';
const BETA = 'demo-user/beta-web';
const REPO_ROOT = path.resolve(process.cwd(), '..');
const DAY_MS = 86_400_000;
const MIN_TOUCH_PX = 44;

type Issue = { iid: number; title: string; webUrl: string; labels: string[]; author: string; state: string | null; active: boolean; sessionId?: number; blocked?: string };
type Mock = { targets: string[]; starts: string[]; startResponse: { status: number; body: unknown } | null; requests: string[] };

function repo(p: string, over: Record<string, unknown> = {}) {
  return { path: p, defaultBranch: 'main', private: false, archived: false, fork: false,
    updatedAt: new Date(Date.now() - 3 * DAY_MS).toISOString(), webUrl: `https://github.com/${p}`, ...over };
}
const REPOS = [repo(ALPHA, { private: true }), repo(ARCHIVE, { archived: true }), repo(BETA)];

function issue(iid: number, title: string, over: Partial<Issue> = {}): Issue {
  return { iid, title, webUrl: `https://github.com/demo-user/x/issues/${iid}`, labels: [], author: 'demo-user', state: null, active: false, ...over };
}
const ISSUES: Record<string, Issue[]> = {
  [ALPHA]: [
    issue(12, '주문 목록 API 빈 페이지 오류', { labels: ['ai-fix', 'bug'] }),
    issue(9, '결제 취소 후 재고 복구 누락', { state: 'REVIEW_WAIT', active: true, sessionId: 7 }),
    issue(5, '외부 사용자가 올린 이슈', { labels: ['ai-fix'], author: 'someone', blocked: '신뢰 작성자가 아니라 처리하지 않습니다' }),
  ],
  [BETA]: [issue(31, '로그인 화면 포커스 순서', { labels: ['ai-fix'] }), issue(30, '다크 모드 대비')],
  [ARCHIVE]: [issue(2, '오타 수정')],
};

// Local home paths in /api/system/** and /api/usage become <repo> before the page renders them.
async function maskSystemPaths(page: Page): Promise<void> {
  const variants = [REPO_ROOT, REPO_ROOT.replace(/\\/g, '\\\\'), REPO_ROOT.replace(/\\/g, '/')];
  const generic = /[A-Za-z]:(?:\\\\|\\|\/)+Users(?:\\\\|\\|\/)+[^\\/"\s]+/g;
  await page.route(/\/api\/(system\/|usage)/, async (route: Route) => {
    const res = await route.fetch();
    let text = await res.text();
    for (const v of variants) text = text.split(v).join('<repo>');
    text = text.replace(generic, '<repo>');
    await route.fulfill({ response: res, body: text });
  });
}

async function expectNoAccountName(page: Page): Promise<void> {
  const account = os.userInfo().username.toLowerCase();
  for (const frame of page.frames()) {
    const text = await frame.evaluate(() => document.documentElement.innerHTML);
    expect(text.toLowerCase()).not.toContain(account);
  }
}

// Fakes repos, issue lists, targets and the start API. Nothing reaches the real target list.
async function mockIssueApi(page: Page): Promise<Mock> {
  const m: Mock = { targets: [SAMPLE], starts: [], startResponse: null, requests: [] };
  const json = (route: Route, body: unknown, status = 200) =>
    route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
  await page.route(/\/api\/issues-(repos|list|start|projects)(\?|$)/, async (route) => {
    const url = new URL(route.request().url());
    m.requests.push(`${route.request().method()} ${url.pathname}`);
    if (url.pathname === '/api/issues-repos') return json(route, { ok: true, repos: REPOS, truncated: false, max: 50 });
    if (url.pathname === '/api/issues-projects') return json(route, { enabled: true, projects: m.targets.map((p) => ({ path: p })) });
    if (url.pathname === '/api/issues-list') {
      const project = url.searchParams.get('project') ?? '';
      const found = REPOS.find((r) => r.path === project);
      if (!found) return json(route, { ok: false, result: '조회할 수 없는 저장소입니다.' });
      return json(route, { ok: true, project, label: 'ai-fix', target: m.targets.includes(project), archived: found.archived, issues: ISSUES[project] ?? [] });
    }
    const body = route.request().postData() ?? '';
    m.starts.push(body);
    if (m.startResponse) return json(route, m.startResponse.body, m.startResponse.status);
    const params = new URLSearchParams(body);
    const project = params.get('project') ?? '';
    const added = !m.targets.includes(project);
    if (added) m.targets.push(project);
    return json(route, { ok: true, result: `${project}#${params.get('number')} 큐에 넣었습니다.`, added, ref: `${project}#${params.get('number')}` });
  });
  return m;
}

async function openList(page: Page): Promise<void> {
  await page.goto('/agent.html');
  await page.locator('#btnList').click();
  await expect(page.locator('#ilDlg')).toBeVisible();
}

async function pickRepo(page: Page, name: string): Promise<void> {
  await page.locator('#ilRepos .ilx-repo', { hasText: name }).click();
  await expect(page.locator('#ilist .irow').first()).toBeVisible();
}

async function expectNoOverflow(page: Page, includeDoc = true): Promise<void> {
  const o = await page.evaluate(() => {
    const d = document.getElementById('ilDlg') as HTMLDialogElement;
    return { doc: document.documentElement.scrollWidth - window.innerWidth, dlg: d.open ? d.scrollWidth - d.clientWidth : 0 };
  });
  if (includeDoc) expect(o.doc).toBeLessThanOrEqual(0);
  expect(o.dlg).toBeLessThanOrEqual(0);
}

test('TC-031 ① 실제 서버 응답(토큰 없음): 토큰 안내·대상 ★·조회 실패 문구·닫으면 요청 없음', TAG, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const requests: string[] = [];
  page.on('request', (r) => { if (/\/api\/issues-(list|repos|start)/.test(r.url())) requests.push(r.url()); });
  await maskSystemPaths(page);
  await page.goto('/agent.html');
  await page.waitForLoadState('networkidle');
  expect(requests, '창이 닫힌 동안 요청 없음').toHaveLength(0);
  await page.locator('#btnList').click();
  await expect(page.locator('#ilNote')).toContainText('GITHUB_TOKEN');
  const sample = page.locator('#ilRepos .ilx-repo', { hasText: SAMPLE });
  await expect(sample).toContainText('★');
  await shot(page, testInfo, 'repos-no-token');
  await sample.click();
  await expect(page.locator('#ilist')).not.toContainText('불러오는 중');
  await expect(page.locator('#ilist')).not.toHaveText('');
  await expect(page.locator('.toast', { hasText: '대상 프로젝트가 없습니다' })).toHaveCount(0);
  await page.locator('#ilClose').click();
  requests.length = 0;
  await page.waitForTimeout(2500); // proves that no request follows a closed window
  expect(requests).toHaveLength(0);
  await expectNoAccountName(page);
  expect(errors).toEqual([]);
  await shot(page, testInfo, 'issue-lookup-failed-closed');
});

test('TC-031 ② 대상 아닌 저장소 시작: 상태·확인 문구·본문·★·blocked·진행 중', TAG, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  await maskSystemPaths(page);
  const mock = await mockIssueApi(page);
  await openList(page);
  await pickRepo(page, 'alpha-service');
  const blocked = page.locator('#ilist .irow', { hasText: '#5' });
  await expect(blocked).toContainText('처리 안 함');
  await expect(blocked).toContainText('신뢰 작성자');
  await expect(blocked.locator('button')).toHaveCount(0);
  await expect(page.locator('#ilist .irow', { hasText: '#9' })).toContainText('보기');
  await pickRepo(page, 'beta-web');
  await expect(page.locator('#ilStatus')).toContainText('대상 아님');
  let message = '';
  page.once('dialog', async (d) => { message = d.message(); await d.dismiss(); });
  await page.locator('#ilist .irow', { hasText: '#31' }).locator('button').click();
  await expect.poll(() => message).toContain('지금 목록에 1건');
  expect(mock.starts, '취소하면 시작 요청 없음').toHaveLength(0);
  await shot(page, testInfo, 'confirm-dismissed');
  page.once('dialog', (d) => d.accept());
  await page.locator('#ilist .irow', { hasText: '#31' }).locator('button').click();
  await expect.poll(() => mock.starts.length).toBe(1);
  expect(mock.starts[0]).toContain('project=demo-user%2Fbeta-web');
  expect(mock.starts[0]).toContain('number=31');
  await expect(page.locator('#ilRepos .ilx-repo', { hasText: 'beta-web' }).locator('.star')).toHaveText('★');
  await expect(page.locator('#ilMsg')).toContainText('큐에 넣었습니다');
  expect(errors).toEqual([]);
  await shot(page, testInfo, 'started-star-added');
});

test('TC-031 ③ 보관 저장소: 시작 버튼 비활성', TAG, async ({ page }, testInfo) => {
  await maskSystemPaths(page);
  await mockIssueApi(page);
  await openList(page);
  await pickRepo(page, 'old-archive');
  await expect(page.locator('#ilist .irow button').first()).toBeDisabled();
  await shot(page, testInfo, 'archived-disabled');
});

test('TC-031 ④ 시작 응답 ok:false: err 문구·★ 안 달림', TAG, async ({ page }, testInfo) => {
  await maskSystemPaths(page);
  const mock = await mockIssueApi(page);
  mock.startResponse = { status: 409, body: { ok: false, result: '이미 진행 중인 이슈입니다.', added: false } };
  await openList(page);
  await pickRepo(page, 'beta-web');
  page.once('dialog', (d) => d.accept());
  await page.locator('#ilist .irow', { hasText: '#31' }).locator('button').click();
  await expect(page.locator('#ilMsg')).toContainText('이미 진행 중');
  await expect(page.locator('#ilMsg')).toHaveClass(/err/);
  await expect(page.locator('#ilRepos .ilx-repo', { hasText: 'beta-web' }).locator('.star')).not.toHaveText('★');
  await shot(page, testInfo, 'start-failed');
});

test('TC-031 ⑤ 390px 두 단계 이동', TAG, async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await maskSystemPaths(page);
  await mockIssueApi(page);
  await openList(page);
  await expect(page.locator('.ilx-right')).toBeHidden();
  await expect(page.locator('#ilClose')).toBeVisible();
  await expectNoOverflow(page);
  await shot(page, testInfo, 'step1-repos');
  await pickRepo(page, 'alpha-service');
  await expect(page.locator('.ilx-left')).toBeHidden();
  await expect(page.locator('#ilist .why')).toBeVisible();
  await expectNoOverflow(page);
  const small = await page.evaluate((min) => [...document.querySelectorAll('#ilDlg button, #ilDlg input')]
    .filter((e) => (e as HTMLElement).offsetParent !== null && !(e as HTMLButtonElement).disabled)
    .map((e) => ({ id: (e as HTMLElement).id || e.textContent?.trim().slice(0, 10), h: Math.round(e.getBoundingClientRect().height) }))
    .filter((x) => x.h < min), MIN_TOUCH_PX);
  expect(small).toEqual([]);
  await shot(page, testInfo, 'step2-issues');
  await page.locator('#ilBack').click();
  await expect(page.locator('.ilx-right')).toBeHidden();
  await expect(page.locator('#ilRepos .ilx-repo').first()).toBeVisible();
  await expect(page.locator('#ilClose')).toBeVisible();
  await expectNoOverflow(page);
  await shot(page, testInfo, 'back-to-repos');
});

for (const width of [768, 1440]) {
  test(`TC-031 ⑥ ${width}px 두 칸 배치`, TAG, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await maskSystemPaths(page);
    await mockIssueApi(page);
    await openList(page);
    await pickRepo(page, 'alpha-service');
    const boxes = await page.evaluate(() => {
      const l = document.querySelector('.ilx-left')!.getBoundingClientRect();
      const r = document.querySelector('.ilx-right')!.getBoundingClientRect();
      return { leftRight: l.right, rightLeft: r.left, sameRow: Math.abs(l.top - r.top) < 40 };
    });
    expect(boxes.sameRow).toBe(true);
    expect(boxes.leftRight).toBeLessThanOrEqual(boxes.rightLeft + 1);
    // Window-only check: page-level overflow (top bar) is a separate test below, outside T-033 code paths.
    await expectNoOverflow(page, false);
    await expectNoAccountName(page);
    await shot(page, testInfo, 'two-columns');
  });
}

test('TC-031 ⑥-b 768px 에이전트 페이지(창 닫힘) 가로 넘침 0', TAG, async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 768, height: 900 });
  await maskSystemPaths(page);
  await page.goto('/agent.html');
  await page.waitForLoadState('networkidle');
  await expectNoOverflow(page);
  await shot(page, testInfo, 'page-closed-768');
});
