import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Browser-side optimistic-updates end-to-end test.
 *
 * spring-boot-chat wires {@code useOptimistic} so outbound user bubbles
 * render with a "(sending…)" suffix the moment Enter is hit, then drop
 * the suffix once {@code confirmAfterMs} (600ms in the sample) auto-
 * confirms. This is the canonical "optimistic UI" flow — atmosphere's
 * room broadcast excludes the sender, so there is no server echo to
 * correlate against; the time-based confirm is the demonstrable contract.
 *
 * The spec asserts:
 *   - Sending a message produces a bubble that *briefly* shows the
 *     "(sending…)" suffix.
 *   - That suffix disappears after the confirmAfterMs window.
 */
test.describe('Optimistic updates (sending → delivered)', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-chat']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('outbound bubble shows "(sending…)" then flips to confirmed', async ({ page }) => {
    await page.goto(server.baseUrl + '/');

    const input = page.getByPlaceholder(/Enter your name to join|Type a message/);
    await expect(input).toBeVisible({ timeout: 20_000 });

    await input.fill('e2e-optimistic-tester');
    await page.keyboard.press('Enter');

    const badge = page.getByTestId('atmosphere-connection-status');
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 15_000 });

    await input.fill('quick-hello');
    await page.keyboard.press('Enter');

    // 600ms window — we MUST be able to observe the suffix at least once.
    // useOptimistic's confirmAfterMs = 600 in the sample; locator.first
    // captures the bubble closest to the input.
    const sendingBubble = page.locator('text=quick-hello  (sending…)');
    await expect(sendingBubble).toBeVisible({ timeout: 500 });

    // After the confirmAfterMs window the suffix must drop.
    await expect(sendingBubble).not.toBeVisible({ timeout: 5_000 });
    await expect(page.locator('text=quick-hello').first()).toBeVisible();
  });
});
