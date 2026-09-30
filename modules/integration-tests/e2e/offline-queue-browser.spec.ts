import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { installNetworkSwitch } from './helpers/network-switch';

/**
 * Browser-side offline-queue end-to-end test.
 *
 * Exercises the client-side resilience loop for messages typed while the
 * transport is disconnected, through the UI spring-boot-chat actually serves:
 * its root redirects to the bundled Atmosphere Console (the sample's React
 * frontend was removed in 341bf3bd3b), whose useAtmosphereChat composable
 * drives atmosphere.js's {@code useOfflineQueue} / {@code OfflineQueue}.
 *
 *   1. Connect over WebSocket (the network switch hides the WebTransport
 *      sidecar so the cut below reaches the socket in use).
 *   2. Cut the network ({@link installNetworkSwitch}). Atmosphere leaves
 *      {@code phase=open}; the Console keeps the input enabled because a
 *      first connect has happened, so sends enqueue instead of dropping.
 *   3. Type two messages — they MUST land in the queue, surfacing as the
 *      "2 queued" chip ({@code data-testid="offline-queue-size"}).
 *   4. Restore the network. The transport drains the queue on reopen
 *      ({@code BaseTransport.drainOfflineQueue}); the chip MUST disappear.
 *   5. The drained messages MUST reach the room — a second member, connected
 *      the whole time, sees both. A queue that emptied without sending would
 *      pass steps 1-4 and fail here.
 *
 * <p>Companion to {@code offline-queue.spec.ts}, which exercises the
 * server-side WebSocket {@code X-Atmosphere-Message-Id} handshake at the raw
 * protocol level.</p>
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

  test('messages typed offline queue, then drain on reconnect', async ({ browser }) => {
    const senderCtx = await browser.newContext();
    const observerCtx = await browser.newContext();
    try {
      const page = await senderCtx.newPage();
      const net = await installNetworkSwitch(page, senderCtx, /\/atmosphere\/chat/);
      const observer = await observerCtx.newPage();

      await observer.goto(server.baseUrl + '/atmosphere/console/');
      await expect(observer.getByTestId('atmosphere-connection-status'))
        .toHaveAttribute('data-phase', 'open', { timeout: 20_000 });

      await page.goto(server.baseUrl + '/atmosphere/console/');
      const badge = page.getByTestId('atmosphere-connection-status');
      await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 20_000 });
      await expect(badge).toHaveAttribute('data-transport', 'websocket');

      // Sanity: no queued messages while online.
      const queueSize = page.getByTestId('offline-queue-size');
      await expect(queueSize).not.toBeVisible();

      // --- Cut the network ---
      await net.down();
      await expect(badge).not.toHaveAttribute('data-phase', 'open', { timeout: 15_000 });

      // --- Type two messages while offline ---
      const input = page.getByTestId('chat-input');
      await input.fill('queued-message-one');
      await page.getByTestId('chat-send').click();
      await input.fill('queued-message-two');
      await page.getByTestId('chat-send').click();

      // Queue chip MUST appear with size 2.
      await expect(queueSize).toBeVisible();
      await expect(queueSize).toHaveText(/2 queued/);

      // --- Restore the network ---
      await net.up();

      // Atmosphere reconnects; phase returns to open.
      await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 30_000 });

      // BaseTransport.drainOfflineQueue empties the queue on reopen.
      await expect(queueSize).not.toBeVisible({ timeout: 15_000 });

      // The drained sends reached the room.
      const observed = observer.getByTestId('message-list');
      await expect(observed).toContainText('queued-message-one', { timeout: 15_000 });
      await expect(observed).toContainText('queued-message-two', { timeout: 15_000 });
    } finally {
      await senderCtx.close();
      await observerCtx.close();
    }
  });
});
