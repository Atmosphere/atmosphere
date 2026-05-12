import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Browser-side presence indicator end-to-end test.
 *
 * spring-boot-chat now derives a "{N} online" badge from the same Room
 * Protocol stream the chat consumes (deliberately not using
 * {@code usePresence} because that hook opens a separate
 * {@code AtmosphereRooms} subscription, which would double the WebSocket
 * against {@code /atmosphere/chat}). The derived membership set advances
 * on {@code join_ack} (room state at join) and on {@code presence} events
 * (subsequent joins/leaves), and the chip in the header carries
 * {@code data-testid="presence-count"} for stable selection.
 *
 * This spec exercises:
 *   - Joining as one user → badge reflects 1 online.
 *   - A second browser context joining the same room → badge advances to 2.
 *   - That second context closing → badge drops back to 1.
 */
test.describe('Presence count badge', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-chat']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('presence badge tracks two-user join/leave cycle', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    try {
      await page1.goto(server.baseUrl + '/');
      const input1 = page1.getByPlaceholder(/Enter your name to join|Type a message/);
      await expect(input1).toBeVisible({ timeout: 20_000 });
      await input1.fill('alice');
      await page1.keyboard.press('Enter');

      const badge1 = page1.getByTestId('presence-count');
      await expect(badge1).toBeVisible({ timeout: 15_000 });
      await expect(badge1).toHaveText(/1 online/);

      // Second user joins — alice's badge advances to 2.
      await page2.goto(server.baseUrl + '/');
      const input2 = page2.getByPlaceholder(/Enter your name to join|Type a message/);
      await expect(input2).toBeVisible({ timeout: 20_000 });
      await input2.fill('bob');
      await page2.keyboard.press('Enter');

      await expect(badge1).toHaveText(/2 online/, { timeout: 15_000 });

      // bob disconnects → alice's badge falls back to 1.
      await ctx2.close();
      await expect(badge1).toHaveText(/1 online/, { timeout: 15_000 });
    } finally {
      await ctx1.close();
      await ctx2.close().catch(() => { /* already closed */ });
    }
  });
});
