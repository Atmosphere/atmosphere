import { test, expect } from '@playwright/test';
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
