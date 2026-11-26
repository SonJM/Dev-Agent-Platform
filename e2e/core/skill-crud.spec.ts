import { test, expect } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

test('TC-007 플랫폼에서 스킬을 등록하고 삭제한다', { tag: ['@core'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const name = `qa-skill-${testInfo.workerIndex}-${Date.now().toString(36)}`;
  page.on('dialog', (dialog) => dialog.accept());

  await page.goto('/platform.html');
  const item = page.locator('#skillList .it', { has: page.locator('b', { hasText: new RegExp(`^${name}$`) }) });
  try {
    await expect(page.locator('#skillList')).not.toContainText('불러오는 중');
    await page.getByRole('button', { name: '+ 스킬 추가' }).click();
    await page.locator('#sfName').fill(name);
    await page.locator('#sfDesc').fill('QA 확인용 스킬');
    await page.locator('#sfBody').fill('- QA 확인용 지침');
    await shot(page, testInfo, 'form-filled');
    await page.locator('#skillForm').getByRole('button', { name: '저장' }).click();
    await expect(item).toBeVisible();
    await expect(item).toContainText('QA 확인용 스킬');
    await shot(page, testInfo, 'registered');

    await item.getByRole('button', { name: '삭제' }).click();
    await expect(item).toHaveCount(0);
    await shot(page, testInfo, 'deleted');
    // A reload shows the server state, not only the client state.
    await page.reload();
    await expect(page.locator('#skillList')).not.toContainText('불러오는 중');
    await expect(item).toHaveCount(0);
    await shot(page, testInfo, 'after-reload');
  } finally {
    await page.request.post(`/api/skills/delete?name=${encodeURIComponent(name)}`);
  }
  expect(errors).toEqual([]);
});
