import { defineConfig, devices } from '@playwright/test';
// @ts-expect-error: plain ESM helper shared with the capture script
import { loadEnv } from '../lib/env.mjs';

const env = loadEnv() as Record<string, string>;

/**
 * End-to-end checks against the REAL running app and backend (no mocks). Start the backend and `npm run dev`, put
 * test accounts in .env.local, then `npm run e2e`. Tests skip, saying why, when the backend or credentials are missing.
 */
export default defineConfig({
  testDir: '.',
  testMatch: /.*\.spec\.ts/,
  fullyParallel: false,
  workers: 1,
  timeout: 240_000,
  // The light-theme regression baseline (scripts/e2e/visual.spec.ts): one flat folder, no platform suffix, ZERO tolerance.
  snapshotPathTemplate: '{testDir}/__baseline__/{arg}{ext}',
  expect: {
    timeout: 15_000,
    toHaveScreenshot: { maxDiffPixels: 0, threshold: 0, animations: 'disabled', caret: 'hide' },
  },
  reporter: [['list']],
  use: { baseURL: env.ARCHYRA_APP_URL, ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
});
