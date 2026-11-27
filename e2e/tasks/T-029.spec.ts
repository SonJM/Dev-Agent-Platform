import { test, expect, type Page, type Route } from '@playwright/test';
import os from 'node:os';
import path from 'node:path';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

const TAG = { tag: ['@task:T-029'] };
const SAMPLE = 'qa-group/qa-sample';
const ALPHA = 'demo-user/alpha-service';
const ARCHIVE = 'demo-user/old-archive';
const BETA = 'demo-user/beta-web';
const LONG = 'demo-user/' + 'very-long-repository-name-without-any-break-'.repeat(3);
const DAY_MS = 86_400_000;
const REPO_ROOT = path.resolve(process.cwd(), '..');

type Repo = { path: string; defaultBranch: string; private: boolean; archived: boolean; fork: boolean; updatedAt: string; webUrl: string; selected: boolean };

function repo(p: string, over: Partial<Repo> = {}): Repo {
  return { path: p, defaultBranch: 'main', private: false, archived: false, fork: false,
    updatedAt: new Date(Date.now() - 3 * DAY_MS).toISOString(), webUrl: `https://github.com/${p}`, selected: false, ...over };
}

const REPOS = [repo(ALPHA, { private: true }), repo(ARCHIVE, { archived: true, defaultBranch: 'trunk' }), repo(BETA)];

function reposBody(repos: Repo[]): string {
  return JSON.stringify({ ok: true, repos, truncated: false, max: 50 });
}

// Local home paths in /api/system/** responses become <repo> before the page renders them,
// so screenshots never carry the account name. The DOM mask in shot() is a second layer.
async function maskSystemPaths(page: Page): Promise<void> {
  const variants = [REPO_ROOT, REPO_ROOT.replace(/\\/g, '\\\\'), REPO_ROOT.replace(/\\/g, '/')];
  const generic = /[A-Za-z]:(?:\\\\|\\|\/)+Users(?:\\\\|\\|\/)+[^\\/"\s]+/g;
  // /api/usage (battery tooltip) also carries the home path in its error text, so it is masked too.
  await page.route(/\/api\/(system\/|usage)/, async (route: Route) => {
    const res = await route.fetch();
    let text = await res.text();
    for (const v of variants) text = text.split(v).join('<repo>');
    text = text.replace(generic, '<repo>');
    await route.fulfill({ response: res, body: text });
  });
}

async function expectNoAccountName(page: Page): Promise<void> {
  const account = os.userInfo().username;
  for (const frame of page.frames()) {
    const text = await frame.evaluate(() => document.documentElement.innerHTML);
    expect(text.toLowerCase()).not.toContain(account.toLowerCase());
  }
}

async function mockRepos(page: Page, repos: Repo[]): Promise<void> {
  await page.route(/\/api\/issues-repos/, (route) => route.fulfill({ contentType: 'application/json', body: reposBody(repos) }));
}

// Captures POST /api/issues-projects/set bodies and answers with the given JSON (never reaches the server).
async function mockSet(page: Page, response: unknown): Promise<string[]> {
  const bodies: string[] = [];
  await page.route(/\/api\/issues-projects\/set/, (route) => {
    bodies.push(route.request().postData() ?? '');
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(response) });
  });
  return bodies;
}

async function openPicker(page: Page) {
  await page.getByRole('button', { name: 'GitHub에서 고르기' }).click();
  const dlg = page.locator('#repoDlg');
  await expect(dlg).toBeVisible();
  return dlg;
}

function row(dlg: ReturnType<Page['locator']>, name: string) {
  return dlg.locator('.rp-row', { hasText: name });
}

test('TC-027 ① 실제 서버 응답(토큰 없음): 안내 문구·직접 입력 칸·현재 대상', TAG, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  await maskSystemPaths(page);
  await page.goto('/platform.html');
  await expect(page.locator('#projList')).not.toContainText('불러오는 중');
  const projects = (await (await page.request.get('/api/issues-projects')).json()) as { enabled?: boolean };
  const dlg = await openPicker(page);
  await expect(dlg.locator('#rpStatus')).toContainText('GITHUB_TOKEN');
  await expect(page.locator('#projIn')).toBeVisible();
  await expect(page.locator('#rpSave')).toBeVisible();
  if (projects.enabled === false) {
    await expect(dlg.locator('#rpStatus')).toContainText('ISSUE_ENABLED');
    await expect(page.locator('#rpSave')).toBeDisabled();
  } else {
    await expect(dlg.locator('#rpExtraBox')).toBeVisible();
    await expect(row(dlg.locator('#rpExtra'), SAMPLE).locator('input')).toBeChecked();
  }
  await expectNoAccountName(page);
  await shot(page, testInfo, 'no-token');
  expect(errors.filter((e) => !e.includes('401'))).toEqual([]);
});

test('TC-027 ② 가로챈 목록: 표시·필터·체크·저장 본문·취소·ok:true 처리', TAG, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  await maskSystemPaths(page);
  await mockRepos(page, REPOS);
  const bodies = await mockSet(page, { ok: true, result: '1개 추가했습니다, 0개 뺐습니다.', rejected: [] });
  await page.goto('/platform.html');
  await expect(page.locator('#projList')).not.toContainText('불러오는 중');
  let dlg = await openPicker(page);

  await expect(row(dlg, ALPHA)).toContainText('비공개');
  await expect(row(dlg, ALPHA)).toContainText('main');
  await expect(row(dlg, ALPHA)).toContainText('일 전');
  await expect(row(dlg, ARCHIVE)).toContainText('보관됨');
  await expect(row(dlg, ARCHIVE)).toContainText('trunk');
  await expect(row(dlg, ARCHIVE).locator('input')).toBeDisabled();
  await expect(row(dlg.locator('#rpExtra'), SAMPLE).locator('input')).toBeChecked();
  await expect(dlg.locator('#rpCount')).toContainText('1개 선택');
  await shot(page, testInfo, 'list');

  await dlg.locator('#rpFilter').fill('beta');
  await expect(row(dlg, BETA)).toBeVisible();
  await expect(row(dlg, ALPHA)).toBeHidden();
  await expect(row(dlg, ARCHIVE)).toBeHidden();
  await dlg.locator('#rpFilter').fill('');
  await expect(row(dlg, ALPHA)).toBeVisible();

  // cancel: no save request
  await row(dlg, ALPHA).locator('input').check();
  await dlg.getByRole('button', { name: '취소' }).click();
  await expect(dlg).toBeHidden();
  expect(bodies).toHaveLength(0);

  // reopen: choices reset to the current target, then check one and save
  dlg = await openPicker(page);
  await expect(row(dlg, ALPHA).locator('input')).not.toBeChecked();
  await row(dlg, ALPHA).locator('input').check();
  await expect(dlg.locator('#rpCount')).toContainText('2개 선택');
  await shot(page, testInfo, 'checked');
  await dlg.getByRole('button', { name: '저장' }).click();
  await expect(dlg).toBeHidden();
  expect(bodies).toHaveLength(1);
  const paths = new URLSearchParams(bodies[0]).getAll('path').sort();
  expect(paths).toEqual([ALPHA, SAMPLE].sort());
  await expect(page.getByText('1개 추가했습니다', { exact: false }).first()).toBeVisible();
  await expect(page.locator('#projList')).toContainText('qa-sample');
  await expectNoAccountName(page);
  await shot(page, testInfo, 'saved');
  expect(errors).toEqual([]);
});

test('TC-027 ③ 390px: 가로 넘침 0, 저장·취소 보임, 행 44px 이상', TAG, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await maskSystemPaths(page);
  await mockRepos(page, [...REPOS, repo(LONG)]);
  await page.goto('/platform.html');
  await expect(page.locator('#projList')).not.toContainText('불러오는 중');
  const dlg = await openPicker(page);
  await expect(row(dlg, 'very-long-repository')).toBeVisible();
  await expect(page.locator('#rpSave')).toBeInViewport();
  await expect(dlg.getByRole('button', { name: '취소' })).toBeInViewport();
  const m = await page.evaluate(() => {
    const d = document.getElementById('repoDlg') as HTMLElement;
    const r = d.getBoundingClientRect();
    const rows = [...d.querySelectorAll('.rp-row')].map((e) => (e as HTMLElement).getBoundingClientRect().height);
    return {
      docOverflow: document.documentElement.scrollWidth - window.innerWidth,
      dlgOverflow: d.scrollWidth - d.clientWidth,
      dlgWidth: r.width, dlgHeight: r.height, vh: window.innerHeight, minRow: Math.min(...rows),
    };
  });
  expect(m.docOverflow).toBeLessThanOrEqual(0);
  expect(m.dlgOverflow).toBeLessThanOrEqual(0);
  expect(m.dlgWidth).toBeLessThanOrEqual(390);
  expect(m.dlgHeight).toBeLessThanOrEqual(m.vh);
  // min-height is 44px; getBoundingClientRect can report 43.99 (sub-pixel), so compare rounded
  expect(Math.round(m.minRow)).toBeGreaterThanOrEqual(44);
  await expectNoAccountName(page);
  await shot(page, testInfo, 'mobile-dialog');
  expect(errors).toEqual([]);
});

test('TC-027 ④ 현재 대상 조회 500·enabled:false: 원인 문구와 저장 비활성', TAG, async ({ page }, testInfo) => {
  await maskSystemPaths(page);
  await mockRepos(page, REPOS);
  let mode: 'fail' | 'off' = 'fail';
  await page.route(/\/api\/issues-projects(\?.*)?$/, (route) => {
    if (route.request().method() !== 'GET') return route.continue();
    if (mode === 'fail') return route.fulfill({ status: 500, contentType: 'application/json', body: '{}' });
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify({ enabled: false, projects: [] }) });
  });
  await page.goto('/platform.html');
  let dlg = await openPicker(page);
  await expect(dlg.locator('#rpStatus')).toContainText('현재 대상 프로젝트를 불러오지 못해');
  await expect(page.locator('#rpSave')).toBeDisabled();
  await expect(row(dlg, ALPHA)).toBeVisible();
  await shot(page, testInfo, 'current-fail');
  await dlg.getByRole('button', { name: '취소' }).click();
  await expect(dlg).toBeHidden();

  mode = 'off';
  dlg = await openPicker(page);
  await expect(dlg.locator('#rpStatus')).toContainText('ISSUE_ENABLED');
  await expect(page.locator('#rpSave')).toBeDisabled();
  await expectNoAccountName(page);
  await shot(page, testInfo, 'agent-off');
});

test('TC-027 ⑤ 저장 응답 ok:false: 성공 토스트 없음, 오류가 창 안에, 창 유지', TAG, async ({ page }, testInfo) => {
  await maskSystemPaths(page);
  await mockRepos(page, REPOS);
  const ERR = '저장소 3개를 저장하지 못했습니다(시험용 오류).';
  const bodies = await mockSet(page, { ok: false, result: ERR });
  await page.goto('/platform.html');
  await expect(page.locator('#projList')).not.toContainText('불러오는 중');
  const dlg = await openPicker(page);
  await row(dlg, BETA).locator('input').check();
  await dlg.getByRole('button', { name: '저장' }).click();
  await expect(dlg.locator('#rpMsg')).toContainText(ERR);
  await expect(dlg).toBeVisible();
  expect(bodies).toHaveLength(1);
  await expect(page.getByText('저장했습니다')).toHaveCount(0);
  await expect(page.getByText('추가했습니다')).toHaveCount(0);
  await expect(page.locator('#rpSave')).toBeEnabled();
  await expectNoAccountName(page);
  await shot(page, testInfo, 'save-error');
});
