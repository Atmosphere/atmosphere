import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { installNetworkSwitch } from './helpers/network-switch';

/**
 * Browser-side optimistic-update end-to-end test.
 *
 * spring-boot-chat's UI is the bundled Atmosphere Console. The sample's React
 * frontend — whose useOptimistic "(sending…)" suffix this spec used to assert —
 * was removed in 341bf3bd3b, and the Console never had that 600 ms timer. The
 * optimistic contract the Console does ship is the offline one
 * (useAtmosphereChat): a message sent while disconnected renders at once as a
 * user bubble marked "(queued)", before any server has seen it, and the mark
 * is reconciled away when the offline queue drains on reconnect — the same
 * bubble, not a duplicate.
 *
 * The spec asserts:
 *   - Online, a sent message renders without the pending mark.
 *   - Offline, the bubble renders immediately with the "(queued)" mark.
 *   - After the reconnect drain, the mark is gone and the bubble is still
 *     there exactly once.
 */
test.describe('Optimistic updates (queued → delivered)', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-chat']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('offline bubble renders at once as "(queued)", then reconciles on drain', async ({ page, context }) => {
    const net = await installNetworkSwitch(page, context, /\/atmosphere\/chat/);
    await page.goto(server.baseUrl + '/atmosphere/console/');

    const badge = page.getByTestId('atmosphere-connection-status');
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 20_000 });

    const input = page.getByTestId('chat-input');
    const list = page.getByTestId('message-list');
    const userBubbles = page.locator('[data-testid="message-bubble"].message--user');

    // Online: no pending mark.
    await input.fill('optimistic-online');
    await page.getByTestId('chat-send').click();
    await expect(userBubbles.filter({ hasText: 'optimistic-online' })).toHaveCount(1);
    await expect(list).not.toContainText('(queued)');

    // Offline: the bubble renders before any server round trip, marked pending.
    await net.down();
    await expect(badge).not.toHaveAttribute('data-phase', 'open', { timeout: 15_000 });
    await input.fill('optimistic-offline');
    await page.getByTestId('chat-send').click();
    const pending = userBubbles.filter({ hasText: 'optimistic-offline' });
    await expect(pending).toHaveCount(1);
    await expect(pending).toContainText('(queued)');

    // Reconnect: the queue drains and the same bubble drops its mark.
    await net.up();
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 30_000 });
    await expect(pending).not.toContainText('(queued)', { timeout: 15_000 });
    await expect(pending).toHaveCount(1);
    await expect(list).not.toContainText('(queued)');
  });
});
