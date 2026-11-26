import { test, expect, type Page } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

// Seeded by e2e/seed/IssueSessions.java (project qa-group/qa-sample, issue numbers 9001-9006).
type Session = { id: number; issueNumber: number; projectPath: string; prUrl?: string | null; state: string };
const PROJECT = 'qa-group/qa-sample';
const FORBIDDEN = /GitLab|\bMR\b|issueIid|mrUrl/;

async function seededSession(page: Page, issueNumber: number): Promise<Session> {
  const res = await page.request.get('/api/agent/overview?limit=80');
  expect(res.status()).toBe(200);
  const body = (await res.json()) as { enabled: boolean; sessions: Session[] };
  expect(body.enabled).toBe(true);
  const found = body.sessions.find((s) => s.projectPath === PROJECT && s.issueNumber === issueNumber);
  if (!found) throw new Error(`seeded session ${issueNumber} missing from overview`);
  return found;
}

test('TC-T012-1 에이전트 화면: 세션 목록 issueNumber·PR 배지, 상세 이슈·PR 링크', { tag: ['@task:T-012'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const review = await seededSession(page, 9002);
  const wait = await seededSession(page, 9001);
  expect(review.prUrl).toContain('/pull/9002');
  expect(wait.prUrl ?? null).toBeNull();

  await page.goto('/agent.html');
  const reviewItem = page.locator('#slist .sitem').filter({ hasText: '#9002' });
  const waitItem = page.locator('#slist .sitem').filter({ hasText: '#9001' });
  await expect(reviewItem).toContainText('qa-sample');
  await expect(reviewItem.locator('.r3 span', { hasText: /^PR$/ })).toBeVisible();
  await expect(waitItem.locator('.r3 span', { hasText: /^PR$/ })).toHaveCount(0);
  await expect(page.locator('#slist')).not.toContainText('undefined');
  await shot(page, testInfo, 'agent-list');

  await reviewItem.click();
  await expect(page.locator('#dProj')).toHaveText(PROJECT);
  await expect(page.locator('#dIid')).toHaveText('이슈 #9002');
  const prLink = page.locator('#dLinks a', { hasText: 'Pull Request' });
  await expect(prLink).toHaveAttribute('href', /\/qa-group\/qa-sample\/pull\/9002$/);
  await expect(page.locator('#dLinks a', { hasText: /^이슈$/ })).toHaveAttribute('href', /\/issues\/9002$/);
  await expect(page.locator('#dLinks')).not.toContainText(FORBIDDEN);
  // Scope: "MR" -> "PR" in screen text. The review-wait guidance must not name an MR either.
  await expect.soft(page.locator('#detail')).not.toContainText(/MR/);
  await shot(page, testInfo, 'agent-detail-pr-link');

  // kind REMOTE event renders in the activity log with its own icon and colour.
  const remote = page.locator('#log .ln.k-REMOTE').first();
  await expect(remote).toBeVisible();
  await expect(remote.locator('.ic')).toHaveText('⬡');
  await expect(remote.locator('.m')).toHaveCSS('color', 'rgb(253, 186, 116)');
  await expect(remote).toContainText('원격 이벤트');
  await page.locator('#log').scrollIntoViewIfNeeded();
  await expect(remote).toBeInViewport();
  await shot(page, testInfo, 'agent-remote-event');
  await page.locator('#log').screenshot({ path: testInfo.outputPath('03b-agent-log-element.png') });

  // A session without a PR shows no Pull Request link.
  await waitItem.click();
  await expect(page.locator('#dIid')).toHaveText('이슈 #9001');
  await expect(page.locator('#dLinks a', { hasText: 'Pull Request' })).toHaveCount(0);

  // Timeline tab also renders the REMOTE event (class k-REMOTE).
  await reviewItem.click();
  await page.locator('button.tab[data-p="tl"]').click();
  await expect(page.locator('#tl .ev.k-REMOTE').first()).toBeVisible();
  await shot(page, testInfo, 'agent-timeline-remote');
  expect(errors).toEqual([]);
});

test('TC-T012-2 홈·메신저·플랫폼: 이슈 표기와 GitHub 용어', { tag: ['@task:T-012'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const wait = await seededSession(page, 9001);

  await page.goto('/home.html');
  const waitCallout = page.locator('#issueActive, .callout.warn').filter({ hasText: '#9001' }).first();
  await expect(waitCallout).toBeVisible();
  await expect(page.locator('#issueActive')).toContainText(`${PROJECT}#9001`);
  await expect(page.locator('#issueActive')).not.toContainText('undefined');
  await expect(page.locator('#issueRecent .sess').first()).toContainText(/qa-(sample|boundary|bulk)#\d+/);
  await expect(page.locator('#issueRecent')).not.toContainText('undefined');
  await expect(page.locator('#issueCount')).not.toHaveText('0');
  await shot(page, testInfo, 'home-sessions');

  await page.goto('/messenger.html');
  await expect(page.getByText('이슈 owner/repo#12')).toBeVisible();
  await expect(page.getByText('질문 owner/repo: 질문 내용')).toBeVisible();
  await expect(page.locator('body')).not.toContainText(FORBIDDEN);
  await shot(page, testInfo, 'messenger-examples');

  await page.goto('/platform.html');
  await expect(page.locator('#projIn')).toHaveAttribute('placeholder', '추가: owner/repo');
  await expect(page.locator('body')).not.toContainText(FORBIDDEN);
  expect(wait.state).toBe('DESIGN_WAIT');
  await shot(page, testInfo, 'platform-project-input');

  await page.goto('/research.html');
  await expect(page.locator('body')).not.toContainText(FORBIDDEN);
  await shot(page, testInfo, 'research');
  expect(errors).toEqual([]);
});
