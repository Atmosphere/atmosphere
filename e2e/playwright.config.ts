import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright config for the Atmosphere v0.5 foundation E2E suite.
 *
 * The tests in this suite require a running Atmosphere sample. Each spec
 * file documents its required sample + port (personal-assistant on 8080,
 * coding-agent on 8081). CI spins up each sample in turn via the
 * Maven Spring Boot run goal; local development runs the sample manually.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI
    ? [['html', { open: 'never' }], ['list']]
    : [['list']],
  use: {
    baseURL: process.env.ATMO_E2E_BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
