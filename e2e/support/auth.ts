import type { Page } from '@playwright/test';

// Local login information comes from environment variables set outside the session.
// Only variable NAMES may appear in code, docs, reports and logs. Never print the values.

function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`missing env: ${name}`);
  return value;
}

export function loginEnv(): { id: string; pw: string } {
  return { id: required('QA_LOGIN_ID'), pw: required('QA_LOGIN_PW') };
}

// Call BEFORE filling login fields: typed values are drawn as dots, so screenshots
// (also the automatic one on failure) never show the login id or password.
export async function maskLoginFields(page: Page, selectors: string[]): Promise<void> {
  const css = selectors.map((s) => `${s} { -webkit-text-security: disc !important; }`).join('\n');
  await page.addStyleTag({ content: css });
}

export function devTokenEnv(): string {
  return required('QA_DEV_TOKEN');
}

export function baseUrl(): string {
  return required('QA_BASE_URL').replace(/\/$/, '');
}
