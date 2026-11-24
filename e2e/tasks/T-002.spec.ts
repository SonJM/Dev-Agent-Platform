import { test, expect } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

// TC-023: real Tomcat path handling must not bypass the Origin check (FR-104).
const EVIL = 'https://evil.example';
const FORBIDDEN_BODY = '이 화면에서만 요청할 수 있습니다.';
const HARMLESS = 'qa-nonexistent-skill';
const PATHS = [
  `/api/skills/delete?name=${HARMLESS}`,
  `/api;x/skills/delete?name=${HARMLESS}`,
  `/%61pi/skills/delete?name=${HARMLESS}`,
  `/api/skills/delete;x?name=${HARMLESS}`,
  `/home.html?name=${HARMLESS}`,
];

test('TC-023 다른 출처의 상태 변경 요청은 경로 표기와 무관하게 403', { tag: ['@task:T-002'] }, async ({ page, request }, testInfo) => {
  const errors = collectConsoleErrors(page);
  await page.goto('/platform.html');
  await expect(page.locator('#skillList')).not.toContainText('불러오는 중');
  await shot(page, testInfo, 'before');

  for (const path of PATHS) {
    for (const origin of [EVIL, 'null']) {
      const res = await request.post(path, { headers: { Origin: origin } });
      expect(res.status(), `${path} origin=${origin}`).toBe(403);
      expect(await res.text()).toContain(FORBIDDEN_BODY);
    }
  }

  const loopback = new URL(page.url()).origin;
  const same = await request.post(PATHS[0], { headers: { Origin: loopback } });
  expect(same.status()).not.toBe(403);
  const none = await request.post(PATHS[0]);
  expect(none.status()).not.toBe(403);

  // Cross-origin GET stays allowed; llm-test must not accept GET any more (405, no LLM call).
  const get = await request.get('/api/system/status', { headers: { Origin: EVIL } });
  expect(get.status()).toBe(200);
  const llmGet = await request.get('/api/llm-test');
  expect(llmGet.status()).toBe(405);

  // Final screen: the platform tab still works for the same-origin screen.
  await page.reload();
  await expect(page.locator('#skillList')).not.toContainText('불러오는 중');
  await expect(page.getByRole('button', { name: '+ 스킬 추가' })).toBeVisible();
  await shot(page, testInfo, 'after-reload');
  expect(errors).toEqual([]);
});
