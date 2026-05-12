import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Browser-side offline-queue end-to-end test.
 *
 * Exercises the full client-side resilience loop for messages typed while
 * the transport is disconnected:
 *
 *   1. Connect via the spring-boot-chat sample's React app
 *      ({@code atmosphere.js} WebSocket transport, {@code useOfflineQueue}
 *      hook driving the {@code OfflineQueue} primitive).
 *   2. Force the browser context offline ({@code context.setOffline(true)}).
 *      Atmosphere transitions out of {@code phase=open}; the UI's pill
 *      flips off "Connected" but the send button stays enabled.
 *   3. Type two messages — they MUST land in the queue, surfacing as the
 *      "N queued" badge ({@code data-testid="offline-queue-size"}).
 *   4. Restore connectivity. The transport drains the queue automatically
 *      on the {@code open} event ({@code BaseTransport.drainOfflineQueue}).
 *      The badge MUST drop back to invisible within a few seconds.
 *
 * Pins the offline-queue contract: queue grows offline, drains on reconnect.
 * A regression in either direction (drop on enqueue, no drain on open)
 * surfaces here instead of in user reports.
 *
 * <p>Companion to {@code offline-queue.spec.ts} which exercises the
 * server-side WebSocket {@code X-Atmosphere-Message-Id} handshake at the
 * raw protocol level. This spec exercises the browser/hook side using the
 * shipped sample frontend.</p>
 */
test.describe('Offline queue (browser)', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-chat']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('messages typed offline queue, then drain on reconnect', async ({ page, context }) => {
    await page.goto(server.baseUrl + '/');

    // Join with a name. spring-boot-chat's first input is the user name.
    const input = page.getByPlaceholder(/Enter your name to join|Type a message/);
    await expect(input).toBeVisible({ timeout: 20_000 });
    await input.fill('e2e-offline-tester');
    await page.keyboard.press('Enter');

    // Wait for the ConnectionStatusBadge to reach phase=open. Assertion is
    // on the data attribute, not the label text, so the test is robust to
    // label tweaks.
    const badge = page.getByTestId('atmosphere-connection-status');
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 15_000 });

    // Sanity: no queued messages while online.
    const queueSize = page.getByTestId('offline-queue-size');
    await expect(queueSize).not.toBeVisible();

    // --- Disconnect the browser ---
    await context.setOffline(true);
    await expect(badge).not.toHaveAttribute('data-phase', 'open', { timeout: 15_000 });

    // --- Type two messages while offline ---
    await input.fill('queued-message-one');
    await page.keyboard.press('Enter');
    await input.fill('queued-message-two');
    await page.keyboard.press('Enter');

    // Queue badge MUST appear with size 2.
    await expect(queueSize).toBeVisible();
    await expect(queueSize).toHaveText(/2 queued/);

    // --- Restore connectivity ---
    await context.setOffline(false);

    // Atmosphere reconnects; phase returns to open.
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 20_000 });

    // BaseTransport.drainOfflineQueue empties the queue on open.
    await expect(queueSize).not.toBeVisible({ timeout: 15_000 });
  });
});
