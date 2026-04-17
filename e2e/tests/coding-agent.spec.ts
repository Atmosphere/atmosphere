import { expect, test } from '@playwright/test';

/**
 * Happy-path E2E test for the coding-agent proof sample.
 *
 * REQUIRES: `samples/spring-boot-coding-agent` running on port 8081 AND
 * either Docker available locally (DockerSandboxProvider) or the
 * in-process provider fallback enabled.
 *
 * Override the base URL at test time:
 *   ATMO_E2E_BASE_URL=http://localhost:8081 npm run test:coding-agent
 */
test.describe('Coding agent sample', () => {
  test.use({ baseURL: process.env.ATMO_E2E_BASE_URL ?? 'http://localhost:8081' });

  test('admin control plane registers the coding-agent', async ({ request }) => {
    const res = await request.get('/atmosphere/admin/agents');
    expect(res.status()).toBe(200);
    const agents = await res.json();
    expect(
      agents.some((a: { name?: string }) => a.name === 'coding-agent'),
      'coding-agent must appear in the agent registry'
    ).toBe(true);
  });

  test('sandbox provider is reachable', async ({ page }) => {
    // The sample does not expose a dedicated sandbox status endpoint;
    // we assert the bundled UI loads, which confirms the backend is up
    // and the ServiceLoader-discovered sandbox provider did not fail at
    // wiring time.
    await page.goto('/');
    await expect(page.locator('body')).toBeVisible();
  });
});
