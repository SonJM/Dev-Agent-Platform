import { test, expect, type FrameLocator, type Page } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

// TC-024: narrow-width (390px) top bar. Screens and a text only that screen shows once loaded.
const SCREENS = [
  { name: '홈', path: '/home.html', marker: /이슈 에이전트/ },
  { name: '에이전트', path: '/agent.html', marker: '세션을 선택하세요' },
  { name: '리서치', path: '/research.html', marker: '아직 스레드가 없습니다' },
  { name: '메신저', path: '/messenger.html', marker: '메신저 알림' },
  { name: '플랫폼', path: '/platform.html', marker: '스킬' },
] as const;
const MIN_TOUCH = 44;
const NARROW = 390;

type Scope = Page | FrameLocator;

async function scrollWidth(page: Page): Promise<number> {
  return page.evaluate(() => document.documentElement.scrollWidth);
}

test('TC-024 390px: 다섯 화면 가로 넘침 없음, 탭 아이콘 이동, 종 버튼 알림함', { tag: ['@task:T-020'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  await page.setViewportSize({ width: NARROW, height: 844 });

  // Each screen opened directly: no horizontal overflow, 5 tabs + bell + theme visible and >= 44px.
  for (const s of SCREENS) {
    await page.goto(s.path);
    const marker = page.getByText(s.marker).first();
    await expect(marker).toBeVisible();
    expect(await scrollWidth(page), `${s.path} scrollWidth`).toBeLessThanOrEqual(NARROW);
    const tabs = page.locator('nav.nav a');
    await expect(tabs).toHaveCount(5);
    for (let i = 0; i < 5; i++) {
      const tab = tabs.nth(i);
      await expect(tab).toBeVisible();
      await expect(tab).toHaveAttribute('aria-label', /.+/);
      await expect(tab.locator('.nl')).toBeHidden();
      const box = await tab.boundingBox();
      expect(box!.height).toBeGreaterThanOrEqual(MIN_TOUCH);
      expect(box!.width).toBeGreaterThanOrEqual(MIN_TOUCH);
      expect(box!.x + box!.width).toBeLessThanOrEqual(NARROW + 1);
    }
    for (const id of ['#noticeBtn', '#themeBtn']) {
      const btn = page.locator(id);
      await expect(btn).toBeVisible();
      const box = await btn.boundingBox();
      expect(box!.width).toBeGreaterThanOrEqual(MIN_TOUCH);
      expect(box!.height).toBeGreaterThanOrEqual(MIN_TOUCH);
      expect(box!.x + box!.width).toBeLessThanOrEqual(NARROW + 1);
    }
    await shot(page, testInfo, `direct-${s.path.replace(/\W/g, '')}`);
  }

  // Tab shell: move through the five screens with the icon tabs only.
  await page.goto('/home.html');
  await expect(page.getByRole('heading', { name: /이슈 에이전트/ })).toBeVisible();
  let scope: Scope = page;
  for (const s of SCREENS.slice(1)) {
    await scope.locator(`nav.nav a[aria-label="${s.name}"]`).click();
    scope = page.frameLocator(`iframe.tabframe[title="${s.path}"]`);
    await expect(scope.getByText(s.marker).first()).toBeVisible();
    expect(await scrollWidth(page), `shell after ${s.name}`).toBeLessThanOrEqual(NARROW);
    await shot(page, testInfo, `tab-${s.path.replace(/\W/g, '')}`);
  }

  // Bell opens the notice panel inside the viewport.
  await scope.locator('#noticeBtn').click();
  const panel = scope.locator('#noticePanel');
  await expect(panel).toBeVisible();
  await expect(panel.getByText('알림').first()).toBeVisible();
  const pbox = await panel.boundingBox();
  expect(pbox!.x).toBeGreaterThanOrEqual(0);
  expect(pbox!.x + pbox!.width).toBeLessThanOrEqual(NARROW + 1);
  expect(await scrollWidth(page), 'shell with notice panel').toBeLessThanOrEqual(NARROW);
  await shot(page, testInfo, 'notice-panel-open');
  expect(errors).toEqual([]);
});

for (const width of [768, 1440]) {
  test(`TC-024 ${width}px: 상단바 기존 모양 유지(탭 이름 보임, 한 줄)`, { tag: ['@task:T-020'] }, async ({ page }, testInfo) => {
    const errors = collectConsoleErrors(page);
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/home.html');
    await expect(page.getByRole('heading', { name: /이슈 에이전트/ })).toBeVisible();
    const tabs = page.locator('nav.nav a');
    await expect(tabs).toHaveCount(5);
    for (let i = 0; i < 5; i++) await expect(tabs.nth(i).locator('.nl')).toBeVisible();
    await expect(page.locator('#noticeBtn')).toBeVisible();
    await expect(page.locator('#themeBtn')).toBeVisible();
    // Single row: brand, nav and the right group share the same top band.
    const brand = await page.locator('.top .brand').boundingBox();
    const nav = await page.locator('nav.nav').boundingBox();
    expect(Math.abs((nav!.y + nav!.height / 2) - (brand!.y + brand!.height / 2))).toBeLessThan(brand!.height);
    if (width >= 1440) await expect(tabs.first().locator('.k')).toBeVisible();
    await shot(page, testInfo, `home-${width}`);
    expect(errors).toEqual([]);
  });
}
