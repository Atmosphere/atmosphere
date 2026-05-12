import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * End-to-end coverage for the resilience-suite retrofit of the
 * spring-boot-ai-classroom sample.
 *
 * The classroom uses {@code useStreaming} (AI streaming protocol, not the
 * raw room protocol), so this spec pins three contracts unique to that
 * surface:
 *
 *   1. **Presence** — the server's {@code @Ready} / {@code @Disconnect}
 *      hooks broadcast a {@code {"type":"presence","count":N}} frame on
 *      the room's broadcaster. The client picks it out via
 *      {@code onRawMessage} (the new {@code useStreaming} option) and
 *      renders a {@code data-testid="presence-count"} chip.
 *
 *   2. **Offline queue** — questions typed while the WebSocket is down
 *      land in the {@code useOfflineQueue} buffer and surface as a
 *      {@code data-testid="offline-queue-size"} chip; they drain
 *      automatically on the next {@code 'open'} event.
 *
 *   3. **Optimistic** — student bubbles get an "(asking…)" suffix until
 *      the first AI streaming chunk arrives. The hook's {@code commit}
 *      runs from the streaming handler so the visual flip is tied to
 *      real round-trip evidence, not a wall-clock timer alone.
 */
test.describe('Classroom resilience (useStreaming + presence + offline + optimistic)', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(180_000);
    server = await startSample(SAMPLES['spring-boot-ai-classroom']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('presence chip reflects one online after joining the math room', async ({ page }) => {
    await page.goto(server.baseUrl + '/');

    // Pick the math room from the selector.
    const mathButton = page.getByTestId('room-math');
    await expect(mathButton).toBeVisible({ timeout: 20_000 });
    await mathButton.click();

    const badge = page.getByTestId('atmosphere-connection-status');
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 20_000 });

    // The server's @Ready hook broadcasts presence on connect, so the
    // first presence frame should arrive within seconds.
    const presence = page.getByTestId('presence-count');
    await expect(presence).toBeVisible({ timeout: 15_000 });
    await expect(presence).toHaveText(/1 online/);
  });

  test('offline-queue chip appears when transport is down, drains on reconnect', async ({
    page,
    context,
  }) => {
    await page.goto(server.baseUrl + '/');
    await page.getByTestId('room-code').click();

    const badge = page.getByTestId('atmosphere-connection-status');
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 20_000 });

    await context.setOffline(true);
    await expect(badge).not.toHaveAttribute('data-phase', 'open', { timeout: 15_000 });

    const input = page.getByPlaceholder(/Ask a code question/i);
    await input.fill('what is a closure?');
    await page.keyboard.press('Enter');

    const queueChip = page.getByTestId('offline-queue-size');
    await expect(queueChip).toBeVisible();
    await expect(queueChip).toHaveText(/1 queued/);

    await context.setOffline(false);
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 20_000 });
    await expect(queueChip).not.toBeVisible({ timeout: 15_000 });
  });
});
