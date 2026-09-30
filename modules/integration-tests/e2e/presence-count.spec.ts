import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { hideWebTransport } from './helpers/network-switch';

/**
 * Browser-side presence indicator end-to-end test.
 *
 * spring-boot-chat's UI is the bundled Atmosphere Console (the sample's React
 * frontend was removed in 341bf3bd3b); `atmosphere.console-room: lobby` makes
 * it join the Room Protocol room. useAtmosphereChat derives the "{N} online"
 * chip ({@code data-testid="presence-count"}) from the same stream the chat
 * consumes — seeded by {@code join_ack} (room state at join), advanced by
 * {@code presence} join/leave deltas — rather than {@code usePresence}, which
 * would open a second subscription against {@code /atmosphere/chat}.
 *
 * This spec exercises:
 *   - One member joins → badge reflects 1 online.
 *   - A second browser context joins the same room → badge advances to 2.
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
      await hideWebTransport(page1);
      await hideWebTransport(page2);

      await page1.goto(server.baseUrl + '/atmosphere/console/');
      await expect(page1.getByTestId('atmosphere-connection-status'))
        .toHaveAttribute('data-phase', 'open', { timeout: 20_000 });

      const badge1 = page1.getByTestId('presence-count');
      await expect(badge1).toBeVisible({ timeout: 15_000 });
      await expect(badge1).toHaveText(/^\s*1 online\s*$/);

      // Second member joins — the first member's badge advances to 2.
      await page2.goto(server.baseUrl + '/atmosphere/console/');
      await expect(page2.getByTestId('atmosphere-connection-status'))
        .toHaveAttribute('data-phase', 'open', { timeout: 20_000 });
      await expect(badge1).toHaveText(/^\s*2 online\s*$/, { timeout: 15_000 });
      await expect(page2.getByTestId('presence-count')).toHaveText(/^\s*2 online\s*$/, { timeout: 15_000 });

      // The second member disconnects → the first member's badge falls back to 1.
      await ctx2.close();
      await expect(badge1).toHaveText(/^\s*1 online\s*$/, { timeout: 15_000 });
    } finally {
      await ctx1.close();
      await ctx2.close().catch(() => { /* already closed */ });
    }
  });
});
