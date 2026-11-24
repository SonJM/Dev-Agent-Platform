import { test, expect, type FrameLocator, type Page } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

// Tab -> a text that only that screen shows once it is loaded.
const TABS = [
  { name: '에이전트', path: '/agent.html', marker: '세션을 선택하세요' },
  { name: '리서치', path: '/research.html', marker: '아직 스레드가 없습니다' },
  { name: '메신저', path: '/messenger.html', marker: '방 설정' },
  { name: '플랫폼', path: '/platform.html', marker: '스킬' },
] as const;

// The tab shell stacks every opened screen as a full-size iframe, so the next tab link
// is clicked inside the screen that is currently on top.
type Scope = Page | FrameLocator;

test('TC-006 홈에서 다섯 탭을 차례로 이동한다', { tag: ['@core'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  await page.goto('/home.html');
  await expect(page.locator('#verTag')).toHaveText(/^v\d+\.\d+\.\d+/);
  await expect(page.getByRole('heading', { name: /이슈 에이전트/ })).toBeVisible();
  await shot(page, testInfo, 'home');

  let scope: Scope = page;
  for (const tab of TABS) {
    await scope.locator('nav.nav a', { hasText: tab.name }).click();
    scope = page.frameLocator(`iframe.tabframe[title="${tab.path}"]`);
    await expect(scope.getByText(tab.marker).first()).toBeVisible();
    await shot(page, testInfo, `tab-${tab.path.replace(/\W/g, '')}`);
  }

  await scope.locator('nav.nav a', { hasText: '홈' }).click();
  await expect(page.getByRole('heading', { name: /이슈 에이전트/ })).toBeVisible();
  await expect(page.locator('#verTag')).toHaveText(/^v\d+\.\d+\.\d+/);
  await shot(page, testInfo, 'home-again');
  expect(errors).toEqual([]);
});
