import { test, expect, type Page } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { hideWebTransport, installNetworkSwitch } from './helpers/network-switch';

/**
 * End-to-end coverage for the {@code sinceId} history-sync handshake.
 *
 * Pins the "no duplicates after reconnect" contract: when a member
 * disconnects, more messages land in the room, and the member reconnects
 * with {@code sinceId = lastSeenId}, the server replays *only* the messages
 * that arrived after the cursor — not the whole room history.
 *
 * spring-boot-chat's UI is the bundled Atmosphere Console (the sample's React
 * frontend was removed in 341bf3bd3b). Its useAtmosphereChat records the
 * server-assigned {@code id} of every Room Protocol message it receives and
 * re-joins with that cursor as {@code sinceId} on reopen; the lobby room keeps
 * 50 messages of history ({@code RoomsConfig}).
 *
 * The room broadcast excludes the sender, so a member never receives its own
 * messages and its cursor only advances on other members' traffic. The spec
 * therefore uses a talker and listeners: Bob talks, Alice listens, loses the
 * network, misses one message, and comes back. Carol stays connected
 * throughout: her receiving the missed message proves the server has it
 * before Alice re-joins, so Alice can only get it through the sinceId replay,
 * never as a live broadcast that raced her reconnect. (Bob's own bubble would
 * prove nothing — the Console renders it locally before the send.)
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

  async function send(page: Page, text: string) {
    await page.getByTestId('chat-input').fill(text);
    await page.getByTestId('chat-send').click();
  }

  test('reconnect with sinceId replays only what was missed', async ({ browser }) => {
    const aliceCtx = await browser.newContext();
    const bobCtx = await browser.newContext();
    const carolCtx = await browser.newContext();
    try {
      const alice = await aliceCtx.newPage();
      const net = await installNetworkSwitch(alice, aliceCtx, /\/atmosphere\/chat/);
      const bob = await bobCtx.newPage();
      await hideWebTransport(bob);
      const carol = await carolCtx.newPage();
      await hideWebTransport(carol);

      const aliceBadge = alice.getByTestId('atmosphere-connection-status');
      await alice.goto(server.baseUrl + '/atmosphere/console/');
      await expect(aliceBadge).toHaveAttribute('data-phase', 'open', { timeout: 20_000 });
      await bob.goto(server.baseUrl + '/atmosphere/console/');
      await expect(bob.getByTestId('atmosphere-connection-status'))
        .toHaveAttribute('data-phase', 'open', { timeout: 20_000 });
      await carol.goto(server.baseUrl + '/atmosphere/console/');
      await expect(carol.getByTestId('atmosphere-connection-status'))
        .toHaveAttribute('data-phase', 'open', { timeout: 20_000 });

      // All three members are in the room before Bob talks.
      await expect(alice.getByTestId('presence-count')).toHaveText(/^\s*3 online\s*$/, { timeout: 15_000 });

      await send(bob, 'hist-msg-1');
      await send(bob, 'hist-msg-2');

      const aliceList = alice.getByTestId('message-list');
      await expect(aliceList).toContainText('hist-msg-1', { timeout: 10_000 });
      await expect(aliceList).toContainText('hist-msg-2', { timeout: 10_000 });

      // --- Alice loses the network; Bob keeps talking ---
      await net.down();
      await expect(aliceBadge).not.toHaveAttribute('data-phase', 'open', { timeout: 15_000 });
      await send(bob, 'hist-msg-3');
      // Carol, still connected, receives it: the server has recorded and
      // broadcast hist-msg-3 before Alice returns.
      await expect(carol.getByTestId('message-list')).toContainText('hist-msg-3', { timeout: 10_000 });
      await expect(aliceList).not.toContainText('hist-msg-3');

      // --- Alice comes back and re-joins with her sinceId cursor ---
      const before = net.connections;
      await net.up();
      await expect(aliceBadge).toHaveAttribute('data-phase', 'open', { timeout: 30_000 });
      expect(net.connections, 'Alice reconnected over a new socket').toBeGreaterThan(before);

      // The message she missed is replayed ...
      await expect(aliceList).toContainText('hist-msg-3', { timeout: 15_000 });
      // ... and nothing she had already seen is replayed again. Give any
      // stray replay frames time to land before counting.
      await alice.waitForTimeout(2_000);
      for (const text of ['hist-msg-1', 'hist-msg-2', 'hist-msg-3']) {
        await expect(aliceList.getByText(text, { exact: false }),
          `${text} must appear exactly once in Alice's feed after the reconnect`).toHaveCount(1);
      }
    } finally {
      await aliceCtx.close();
      await bobCtx.close();
      await carolCtx.close();
    }
  });
});
