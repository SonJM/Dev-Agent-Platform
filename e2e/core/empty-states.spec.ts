import { test, expect } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

test('TC-010 세션·스레드가 없을 때 에이전트·리서치 탭이 빈 상태 안내를 보인다', { tag: ['@core'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  await page.goto('/agent.html');
  await expect(page.getByText(/세션이 없습니다/).first()).toBeVisible();
  await expect(page.getByText('세션을 선택하세요')).toBeVisible();
  await shot(page, testInfo, 'agent-empty');

  await page.goto('/research.html');
  await expect(page.getByText('아직 스레드가 없습니다')).toBeVisible();
  await expect(page.locator('#projChips')).toContainText('qa-sample');
  await shot(page, testInfo, 'research-empty');
  expect(errors).toEqual([]);
});
