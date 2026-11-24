import { test, expect } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

test('TC-009 알림함을 열면 시작 알림이 보이고 읽음 뒤 배지가 사라진다', { tag: ['@core'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  // A browser that never read the notices: the start notice counts as unread.
  await page.addInitScript(() => {
    if (!localStorage.getItem('da-notices')) {
      localStorage.setItem('da-notices', JSON.stringify({ seen: 1, cleared: 0, items: [] }));
    }
  });
  await page.goto('/platform.html');
  const badge = page.locator('#noticeBtn .nbadge');
  await expect(badge).toBeVisible();
  await shot(page, testInfo, 'badge');

  await page.locator('#noticeBtn').click();
  await expect(page.locator('#noticePanel')).toBeVisible();
  await expect(page.locator('#noticeList').getByText('Dev Agent 시작됨')).toBeVisible();
  await shot(page, testInfo, 'panel-open');

  await page.keyboard.press('Escape');
  await expect(page.locator('#noticePanel')).toBeHidden();
  await expect(page.locator('#noticeBtn .nbadge')).toHaveCount(0);
  await shot(page, testInfo, 'badge-gone');
  expect(errors).toEqual([]);
});
