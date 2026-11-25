import { test, expect, type Page } from '@playwright/test';
import { shot } from '../support/shot.ts';
import { collectConsoleErrors } from '../support/console.ts';

const BUNDLE_FORMAT = 'dev-agent-bundle';
const LITERAL_HTML = '<b>literal</b>';

// The bundle is handed to the hidden file input as an in-memory buffer (no file on disk).
async function pickBundle(page: Page, bundle: unknown): Promise<void> {
  await page.locator('#bundleFile').setInputFiles({
    name: 'qa-bundle.json',
    mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify(bundle), 'utf-8'),
  });
}

async function mcpNames(page: Page): Promise<string[]> {
  const res = await page.request.get('/api/mcp-servers');
  const body = (await res.json()) as { servers: { name: string }[] };
  return body.servers.map((s) => s.name);
}

test('TC-025 stdio MCP 묶음: 확인 창·체크 전 비활성·취소 시 저장 없음·체크 후 저장', { tag: ['@task:T-025'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const name = `qa-mcp-${Date.now().toString(36)}`;
  const bundle = {
    format: BUNDLE_FORMAT,
    mcpServers: [{ name, type: 'stdio', command: 'node', args: `--qa-flag\n${LITERAL_HTML}`, enabled: true }],
  };
  const dlg = page.locator('#bundleDlg');
  const cancel = dlg.locator('.bc-btns').getByRole('button', { name: '취소' });
  const ok = dlg.locator('.bc-btns').getByRole('button', { name: '가져오기' });

  await page.goto('/platform.html');
  try {
    await expect(page.locator('#skillList')).not.toContainText('불러오는 중');
    await pickBundle(page, bundle);
    await expect(dlg).toBeVisible();
    await expect(dlg.locator('#bcCount')).toContainText('MCP 서버 1');
    await expect(dlg.locator('#bcStdio')).toContainText(name);
    await expect(dlg.locator('#bcStdio')).toContainText('node');
    await expect(dlg.locator('#bcStdio')).toContainText('--qa-flag');
    // Bundle content must be shown as text, never parsed as HTML.
    await expect(dlg.locator('#bcStdio')).toContainText(LITERAL_HTML);
    await expect(dlg.locator('#bcStdio b')).toHaveCount(0);
    await expect(dlg.locator('#bcWarn')).toBeVisible();
    await expect(ok).toBeDisabled();
    await shot(page, testInfo, 'dialog-unchecked');

    await cancel.click();
    await expect(dlg).toBeHidden();
    expect(await mcpNames(page)).not.toContain(name);
    await shot(page, testInfo, 'cancelled');

    await pickBundle(page, bundle);
    await expect(dlg).toBeVisible();
    await expect(ok).toBeDisabled();
    await dlg.locator('#bcAck').check();
    await expect(ok).toBeEnabled();
    await shot(page, testInfo, 'dialog-checked');
    await ok.click();
    await expect(dlg).toBeHidden();
    await expect.poll(async () => mcpNames(page)).toContain(name);
    await shot(page, testInfo, 'imported');
  } finally {
    await page.request.post(`/api/mcp-servers/delete?name=${encodeURIComponent(name)}`);
  }
  expect(await mcpNames(page)).not.toContain(name);
  expect(errors).toEqual([]);
});

test('TC-025 stdio 없는 묶음(스킬 1개): 경고 없이 항목 수만, 가져오면 스킬 반영', { tag: ['@task:T-025'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const name = `qa-bskill-${Date.now().toString(36)}`;
  const bundle = {
    format: BUNDLE_FORMAT,
    skills: [{ name, description: 'QA 묶음 스킬', body: '- QA 확인용 지침', enabled: true }],
  };
  const dlg = page.locator('#bundleDlg');
  const ok = dlg.locator('.bc-btns').getByRole('button', { name: '가져오기' });
  const item = page.locator('#skillList .it', { has: page.locator('b', { hasText: new RegExp(`^${name}$`) }) });

  await page.goto('/platform.html');
  try {
    await expect(page.locator('#skillList')).not.toContainText('불러오는 중');
    await pickBundle(page, bundle);
    await expect(dlg).toBeVisible();
    await expect(dlg.locator('#bcCount')).toContainText('스킬 1');
    await expect(dlg.locator('#bcCount')).toContainText('MCP 서버 0');
    await expect(dlg.locator('#bcWarn')).toBeHidden();
    await expect(dlg.locator('#bcStdio')).toBeHidden();
    await expect(dlg.locator('#bcAckRow')).toBeHidden();
    await expect(ok).toBeEnabled();
    await shot(page, testInfo, 'dialog-no-stdio');

    await ok.click();
    await expect(dlg).toBeHidden();
    await expect(item).toBeVisible();
    await expect(item).toContainText('QA 묶음 스킬');
    await shot(page, testInfo, 'skill-imported');
  } finally {
    await page.request.post(`/api/skills/delete?name=${encodeURIComponent(name)}`);
  }
  expect(errors).toEqual([]);
});

test('TC-025 390px: 확인 창 가로 넘침 0, 버튼이 보인다', { tag: ['@task:T-025'] }, async ({ page }, testInfo) => {
  const errors = collectConsoleErrors(page);
  const name = `qa-mcp-m-${Date.now().toString(36)}`;
  const longArg = 'x'.repeat(200);
  const bundle = {
    format: BUNDLE_FORMAT,
    mcpServers: [{ name, type: 'stdio', command: 'node', args: `--long\n${longArg}`, enabled: true }],
  };
  const dlg = page.locator('#bundleDlg');
  const cancel = dlg.locator('.bc-btns').getByRole('button', { name: '취소' });
  const ok = dlg.locator('.bc-btns').getByRole('button', { name: '가져오기' });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/platform.html');
  await pickBundle(page, bundle);
  await expect(dlg).toBeVisible();
  await expect(dlg.locator('#bcStdio')).toContainText(name);
  await expect(cancel).toBeInViewport();
  await expect(ok).toBeInViewport();
  const overflow = await dlg.evaluate((el) => {
    const box = el.getBoundingClientRect();
    const inner = Array.from(el.querySelectorAll('*')).some((c) => c.getBoundingClientRect().right > box.right + 1);
    return { dialog: el.scrollWidth - el.clientWidth, inner, withinViewport: box.right <= window.innerWidth + 1 };
  });
  expect(overflow.dialog).toBeLessThanOrEqual(1);
  expect(overflow.inner).toBe(false);
  expect(overflow.withinViewport).toBe(true);
  await shot(page, testInfo, 'dialog-390');

  await cancel.click();
  await expect(dlg).toBeHidden();
  expect(await mcpNames(page)).not.toContain(name);
  await shot(page, testInfo, 'cancelled-390');
  expect(errors).toEqual([]);
});
