import { test, expect } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

test('TC-008 리서치에서 루틴을 등록하고 삭제한다(예약 전이라 실행되지 않음)', { tag: ['@core'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const name = `qa-routine-${Date.now().toString(36)}`;
  page.on('dialog', (dialog) => dialog.accept());
  const item = page.locator('#rlist .ritem', { hasText: name });
  try {
    // The launch script registers one fake target project (GITLAB_PROJECTS); a routine needs one.
    await page.goto('/research.html');
    await expect(page.locator('#projChips')).toContainText('qa-sample');
    await page.locator('#qIn').fill('QA 확인용 질문입니다');
    await page.locator('#rtBtn').click();
    await page.locator('#rfName').fill(name);
    // Late time of day: a time already past would run once at the next tick.
    await page.locator('#rfTime').fill('23:59');
    await shot(page, testInfo, 'form-filled');
    await page.getByRole('button', { name: '루틴 저장' }).click();
    await expect(item).toBeVisible();
    await expect(item).toContainText('23:59');
    await shot(page, testInfo, 'registered');

    const listed = await (await page.request.get('/api/routines')).json();
    const saved = listed.routines.find((r: { name: string }) => r.name === name);
    expect(saved.lastJobId ?? null).toBeNull();

    await item.getByTitle('삭제').click();
    await expect(item).toHaveCount(0);
    const after = await (await page.request.get('/api/routines')).json();
    expect(after.routines.some((r: { name: string }) => r.name === name)).toBe(false);
    await shot(page, testInfo, 'deleted');
  } finally {
    const listed = await (await page.request.get('/api/routines')).json();
    for (const r of listed.routines.filter((x: { name: string }) => x.name === name)) {
      await page.request.post('/api/routines/delete', { form: { id: String(r.id) } });
    }
  }
  expect(errors).toEqual([]);
});
