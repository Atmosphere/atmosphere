import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * End-to-end coverage for the {@code sinceId} history-sync handshake.
 *
 * Pins the "no duplicates after reconnect" contract: when a client
 * disconnects, more messages land in the room, and the client reconnects
 * with {@code sinceId = lastSeenId}, the server replays *only* the
 * messages that arrived after the cursor — not the full broadcaster
 * cache. Before this work, every reconnect replayed everything and the
 * UI showed duplicates.
 *
 * The {@code spring-boot-chat} sample has been wired to:
 *   - Observe incoming {@code message.id} fields via {@code useMessageHistory}
 *   - Re-send the join frame with {@code sinceId} inside its
 *     {@link useAtmosphere#onReopen} callback
 *
 * The test drives the visible UI surface (status badge, message list)
 * via {@code page.context().setOffline(...)}; both legs of the round
 * trip run in one browser context to keep the test self-contained.
 */
test.describe('History sync (sinceId on reconnect)', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-chat']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('reconnect with sinceId does not duplicate messages already seen', async ({
    page,
    context,
  }) => {
    await page.goto(server.baseUrl + '/');

    const input = page.getByPlaceholder(/Enter your name to join|Type a message/);
    await expect(input).toBeVisible({ timeout: 20_000 });

    // Join with a name.
    await input.fill('e2e-history-tester');
    await page.keyboard.press('Enter');

    const badge = page.getByTestId('atmosphere-connection-status');
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 15_000 });

    // Send a couple of online messages — these populate the server-side
    // history buffer with monotonic ids. Each broadcast echoes back to
    // the sender carrying its server-assigned id, which the history-sync
    // hook captures.
    await input.fill('msg-1');
    await page.keyboard.press('Enter');
    await input.fill('msg-2');
    await page.keyboard.press('Enter');

    // Wait until both messages have appeared in the local feed.
    const messageList = page.locator('text=msg-1');
    await expect(messageList).toBeVisible({ timeout: 10_000 });
    await expect(page.locator('text=msg-2')).toBeVisible({ timeout: 10_000 });

    // --- Disconnect, then bring back online ---
    await context.setOffline(true);
    await expect(badge).not.toHaveAttribute('data-phase', 'open', { timeout: 15_000 });

    await context.setOffline(false);
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 20_000 });

    // Wait for the reconnect's join_ack / history-replay window to settle.
    await page.waitForTimeout(2_000);

    // Verify no duplicates of msg-1 / msg-2 appeared in the chat list.
    // Each should still occur exactly once.
    //
    // Note: the local echo adds one "msg-1" bubble at send time, and
    // the server's broadcast adds zero copies (the room broadcast
    // exclude-sender path keeps the sender from receiving its own
    // message). So after reconnect with sinceId=lastSeenId the chat
    // should still show exactly 1 of each.
    const msgOneCount = await page.locator('text=msg-1').count();
    const msgTwoCount = await page.locator('text=msg-2').count();
    expect(msgOneCount).toBe(1);
    expect(msgTwoCount).toBe(1);
  });
});
