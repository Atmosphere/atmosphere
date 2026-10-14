import { test, expect } from '@playwright/test';
import { WebSocket } from 'ws';
import { ChatPage } from './helpers/chat-page';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['embedded-jetty-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Embedded Jetty WebSocket Chat', () => {
  test('page loads and connects', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();
  });

  test('user can join and send messages', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('Alice');
    await chat.sendMessage('Hello from embedded Jetty!');
    await chat.expectMessage('Hello from embedded Jetty!');
  });

  test('status bar shows Connected', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();
    await chat.expectStatus('Connected');
  });

  test('message bubbles display author', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    // The Console's broadcast dialect sends {author: 'console', message} and
    // renders the server echo author-prefixed — the echo proves the frame
    // made the full wire round-trip through the broadcaster.
    await chat.sendMessage('Who am I?');
    await chat.expectMessageFrom('console', 'Who am I?');
  });

  test('input clears after sending', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('Dave');
    await chat.sendMessage('test message');
    await expect(chat.input).toHaveValue('');
  });

  test('multiple messages appear in order', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('Eve');
    await chat.sendMessage('First message');
    await chat.expectMessage('First message');
    await chat.sendMessage('Second message');
    await chat.expectMessage('Second message');
  });
});

test.describe('Embedded Jetty protocol handshake', () => {
  test('handshake is length-prefixed and an idle connection survives Jetty\'s idle timeout', async () => {
    // The Console subscribes with X-Atmosphere-TrackMessageSize=true and reads
    // every frame as "<length>|<message>". Without TrackMessageSizeInterceptor
    // the handshake went out as "<uuid>|0|X|", the client took the uuid for the
    // length and ran with uuid 0, so each reconnect created a new resource. With
    // no heartbeat inside Jetty's 30 s idle timeout, every idle connection was
    // closed and reconnected. A raw client sees both on the wire.
    test.setTimeout(90_000);
    const wsUrl = server.baseUrl.replace(/^http/, 'ws') + '/chat'
      + '?X-Atmosphere-tracking-id=0&X-Atmosphere-Framework=5.0.48&X-Atmosphere-Transport=websocket'
      + '&X-Atmosphere-TrackMessageSize=true&X-atmo-protocol=true';
    const ws = new WebSocket(wsUrl);
    const frames: string[] = [];
    let closedAt: number | null = null;
    const openedAt = Date.now();
    ws.on('message', (data) => frames.push(data.toString()));
    ws.on('close', () => { closedAt = Date.now(); });
    try {
      await expect.poll(() => frames.length, { timeout: 10_000 }).toBeGreaterThan(0);
      expect(frames[0]).toMatch(/^\d+\|[0-9a-f-]{36}\|\d+\|/);

      await new Promise((r) => setTimeout(r, 40_000));
      expect(closedAt, `closed after ${closedAt === null ? '' : (closedAt - openedAt) / 1000}s idle`).toBeNull();
    } finally {
      ws.close();
    }
  });
});

test.describe('Jetty HTTP/3 (QUIC) Support', () => {
  test('console header shows the sample subtitle', async ({ page }) => {
    // The Console renders the subtitle its /api/console/info stand-in
    // (ConsoleInfoServlet) reports for this sample — the old bespoke page's
    // container-info banner is gone with the bespoke UI.
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByText('WebSocket broadcast chat on embedded Jetty'))
      .toBeVisible({ timeout: 10_000 });
  });

  test('sample root redirects to the console', async ({ page }) => {
    // The meta-refresh redirect is the sample's whole remaining root page.
    await page.goto(server.baseUrl);
    await page.waitForURL(/\/atmosphere\/console\//, { timeout: 10_000 });
    await expect(page.getByTestId('chat-layout')).toBeVisible({ timeout: 10_000 });
  });

  test('chat works over WebSocket on Jetty 12', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('Http3Tester');
    await chat.sendMessage('HTTP/3 connector is live on QUIC!');
    await chat.expectMessage('HTTP/3 connector is live on QUIC!');
  });
});
