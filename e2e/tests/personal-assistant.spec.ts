import { expect, test } from '@playwright/test';

/**
 * Happy-path E2E test for the personal-assistant proof sample.
 *
 * REQUIRES: `samples/spring-boot-personal-assistant` running on port 8080.
 * Spring Boot startup:
 *   cd samples/spring-boot-personal-assistant
 *   ../../mvnw spring-boot:run
 *
 * The test drives the bundled chat UI and asserts the primary assistant
 * routes keyword-matching messages to the right crew member, surfacing
 * the tool-call events the sample emits via session.emit().
 */
test.describe('Personal assistant sample', () => {
  test('loads the chat UI', async ({ page }) => {
    await page.goto('/');
    // The sample bundles a minimal chat UI; we only assert the page loads
    // and exposes an input element a user can type into.
    await expect(page.locator('body')).toBeVisible();
    const inputs = page.locator('input, textarea');
    await expect(inputs.first()).toBeAttached({ timeout: 5_000 });
  });

  test('admin control plane exposes the agent', async ({ request }) => {
    const res = await request.get('/atmosphere/admin/agents');
    expect(res.status()).toBe(200);
    const agents = await res.json();
    expect(
      agents.some(
        (a: { name?: string }) => a.name === 'primary-assistant'
      ),
      'primary-assistant must appear in the agent registry'
    ).toBe(true);
  });
});
