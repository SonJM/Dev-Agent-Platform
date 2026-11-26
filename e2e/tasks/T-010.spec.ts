import { test, expect } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

// The app runs with GITLAB_ENABLED=true and no GITHUB_TOKEN; the GitHub API address (if set) is unreachable.
const TOKEN_LIKE = /(ghp_|github_pat_)[A-Za-z0-9_]+/;

test('TC-015 설치 점검의 GitHub 항목: 토큰 없음이면 원인과 고치는 방법을 보이고 GitLab 표기 없음', { tag: ['@task:T-010'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);

  await page.goto('/platform.html');
  const list = page.locator('#doctorList');
  await expect(list).not.toContainText('점검 중');
  await shot(page, testInfo, '설치 점검 목록');

  const item = list.locator('.doc', { has: page.locator('b', { hasText: /^GitHub$/ }) });
  await expect(item).toHaveCount(1);
  await expect(item.locator('.badge')).toContainText('필요');
  await expect(item).toContainText('GITHUB_TOKEN');
  await expect(item).toContainText('Fine-grained');
  await expect(item).toContainText('Contents');
  await expect(item).toContainText('재시작');
  await expect(list).not.toContainText('GitLab');
  await expect(list).not.toContainText('write_repository');

  // The raw API result must carry no token-looking value.
  const res = await page.request.get('/api/system/doctor?refresh=true');
  expect(res.status()).toBe(200);
  const raw = await res.text();
  expect(raw).not.toMatch(TOKEN_LIKE);
  expect(raw).not.toMatch(/Authorization/i);
  const body = JSON.parse(raw) as { checks: { key: string; status: string }[] };
  const gh = body.checks.find((c) => c.key === 'github');
  expect(gh?.status).toBe('fail');
  expect(body.checks.some((c) => c.key === 'gitlab')).toBe(false);

  await item.scrollIntoViewIfNeeded();
  await shot(page, testInfo, 'GitHub 항목 토큰 없음 안내');
  expect(errors.filter((e) => !/favicon/i.test(e))).toEqual([]);
});
