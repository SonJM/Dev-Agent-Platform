import { defineConfig, devices } from '@playwright/test';

// qa-run.sh sets QA_BASE_URL (origin of the health URL unless preset) and QA_REPORT_JSON.
// Do not change workers, screenshot or trace: the QA report depends on them.
export default defineConfig({
  testDir: '.',
  testMatch: ['core/**/*.spec.ts', 'tasks/**/*.spec.ts'],
  outputDir: 'test-results',
  workers: 1,
  fullyParallel: false,
  retries: 0,
  forbidOnly: true,
  timeout: 60_000,
  reporter: [['list'], ['json', { outputFile: process.env.QA_REPORT_JSON ?? 'test-results/report.json' }]],
  use: {
    baseURL: process.env.QA_BASE_URL,
    screenshot: 'on',
    trace: 'retain-on-failure',
    viewport: { width: 1280, height: 800 },
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } } }],
});
