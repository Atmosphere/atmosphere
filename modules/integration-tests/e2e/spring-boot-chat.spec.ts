import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { hideWebTransport } from './helpers/network-switch';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring Boot Chat', () => {
  test('page loads with console layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('console connects to chat handler', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });
  });

  test('user can send a message', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });

    await page.getByTestId('chat-input').fill('Hello from Playwright!');
    await page.getByTestId('chat-send').click();

    // User message should appear in the message list
    await expect(page.getByTestId('message-list')).toContainText('Hello from Playwright!', { timeout: 10_000 });
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });

    await page.getByTestId('chat-input').fill('test message');
    await page.getByTestId('chat-send').click();

    // Input should be cleared after sending
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('send button is disabled when not connected', async ({ page }) => {
    // Navigate but don't wait for connection — check initial state
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    // Once connected, send button should be usable with non-empty input
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });
    // With empty input, send should be disabled
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  // Regression (F1): the console used to unconditionally probe optional-module
  // endpoints for feature-detection, which 404'd on a plain chat sample and
  // logged red console errors. The console now gates those tabs on the
  // /api/console/info capability flags (hasInteractions / hasVerifier), so it
  // never fetches an endpoint that isn't there.
  test('does not probe optional endpoints that 404', async ({ page }) => {
    const notFound: string[] = [];
    page.on('response', (r) => {
      const u = new URL(r.url()).pathname;
      if (
        r.status() === 404 &&
        (u === '/api/interactions' || u === '/api/admin/verifier/summary')
      ) {
        notFound.push(u);
      }
    });
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    // Let onMounted's /api/console/info fetch + any gated probes settle.
    await page.waitForTimeout(1500);
    expect(notFound, `unexpected 404 probes: ${notFound.join(', ')}`).toEqual([]);
  });

  // Regression (browser-sweep ISSUE-1): the console used to drop BOTH halves
  // of broadcast mode — outbound, it pushed the raw prompt string, which the
  // chat sample's JacksonDecoder cannot decode (message silently dropped
  // server-side); inbound, event-less {author, message, time} frames from
  // other clients fell through handleStreamingEvent's default case and never
  // rendered. The earlier "user can send a message" test passed on the LOCAL
  // user bubble alone, masking all of it. This test pins the real contract:
  // a message sent from one console client must render on ANOTHER connected
  // console client (and echo back to the sender) through the server round-trip.
  test('broadcast reaches another connected console client', async ({ browser }) => {
    const ctxA = await browser.newContext();
    const ctxB = await browser.newContext();
    try {
      const a = await ctxA.newPage();
      const b = await ctxB.newPage();
      await a.goto(server.baseUrl + '/atmosphere/console/');
      await b.goto(server.baseUrl + '/atmosphere/console/');
      await expect(a.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });
      await expect(b.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });

      // Both consoles join the sample's Room Protocol room (console-room:
      // lobby); the presence chip only renders after the server's join_ack,
      // so "2 online" on both pages IS a proven round-trip for each client.
      await expect(a.getByTestId('presence-count')).toHaveText(/\d+ online/, { timeout: 15_000 });
      await expect(b.getByTestId('presence-count')).toHaveText(/\d+ online/, { timeout: 15_000 });

      await a.getByTestId('chat-input').fill('cross-client broadcast probe');
      await a.getByTestId('chat-send').click();

      // The other member renders the room broadcast prefixed with the
      // sender's memberId ({type:'message', from:'console-xxxx', data}).
      // Room broadcasts deliberately exclude the sender, so there is no
      // sender-side echo — the receiver's bubble is the round-trip proof.
      await expect(b.getByTestId('message-list'))
        .toContainText('cross-client broadcast probe', { timeout: 10_000 });
      await expect(b.getByTestId('message-list'))
        .toContainText(/console-[0-9a-f]+: cross-client broadcast probe/, { timeout: 10_000 });
    } finally {
      await ctxA.close();
      await ctxB.close();
    }
  });

  // Regression: the chat closes a connection it has not heard from for 120 s
  // (MAX_INACTIVE) and its handshake asks clients for a heartbeat every 30 s.
  // The Console in room mode (console-room: lobby) subscribed with the
  // protocol off, so it never read that handshake, never sent a heartbeat,
  // and a quiet page was closed every ~122 s, rejoining under a new tracking
  // id. A quiet page must now stay on its one connection past the timeout.
  test('a quiet room-mode console heartbeats and outlives the idle timeout', async ({ browser }) => {
    test.setTimeout(240_000);
    const ctxA = await browser.newContext();
    const ctxB = await browser.newContext();
    try {
      const a = await ctxA.newPage();
      // A WebSocket, which Playwright can observe frame by frame.
      await hideWebTransport(a);
      const sockets: { url: string; sent: string[]; received: string[]; closed: boolean }[] = [];
      a.on('websocket', (ws) => {
        if (!ws.url().includes('/atmosphere/chat')) return;
        const record = { url: ws.url(), sent: [] as string[], received: [] as string[], closed: false };
        sockets.push(record);
        ws.on('framesent', (f) => record.sent.push(String(f.payload)));
        ws.on('framereceived', (f) => record.received.push(String(f.payload)));
        ws.on('close', () => { record.closed = true; });
      });

      await a.goto(server.baseUrl + '/atmosphere/console/');
      await expect(a.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });
      await expect(a.getByTestId('presence-count')).toHaveText(/\d+ online/, { timeout: 15_000 });

      expect(sockets).toHaveLength(1);
      const socket = sockets[0];
      // Soft: a client without the protocol still shows below what it costs.
      expect.soft(socket.url).toContain('X-atmo-protocol=true');
      // The server's first write names the tracking id and the 30 s heartbeat.
      expect.soft(socket.received[0], 'the protocol handshake').toMatch(/^[0-9a-f-]{36}\|30000\|X\|$/);

      // Quiet past the 120 s idle timeout (the sweep runs every 2 s). This
      // page is the only connection open meanwhile.
      const logMark = server.getOutput().length;
      await a.waitForTimeout(130_000);

      expect.soft(socket.sent.filter((f) => f === 'X').length, 'heartbeats sent').toBeGreaterThanOrEqual(3);
      expect(server.getOutput().slice(logMark)).not.toContain('unexpectedly disconnected');
      expect(socket.closed, 'the server closed the quiet page').toBe(false);
      expect(sockets, 'the quiet page reconnected').toHaveLength(1);

      // Still a live member: another member's broadcast reaches it.
      const b = await ctxB.newPage();
      await hideWebTransport(b);
      await b.goto(server.baseUrl + '/atmosphere/console/');
      await expect(b.getByTestId('presence-count')).toHaveText(/\d+ online/, { timeout: 15_000 });
      await b.getByTestId('chat-input').fill('after the idle timeout');
      await b.getByTestId('chat-send').click();
      await expect(a.getByTestId('message-list'))
        .toContainText(/console-[0-9a-f]+: after the idle timeout/, { timeout: 10_000 });
    } finally {
      await ctxA.close();
      await ctxB.close();
    }
  });
});
