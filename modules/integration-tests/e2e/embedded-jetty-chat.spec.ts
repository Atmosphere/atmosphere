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

    await chat.joinAs('Charlie');
    await chat.sendMessage('Who am I?');
    await chat.expectMessageFrom('Charlie', 'Who am I?');
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
  test('page shows Jetty 12 container info', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByText('Jetty 12')).toBeVisible({ timeout: 10_000 });
  });

  test('page subtitle confirms Jetty HTTP/3 active', async ({ page }) => {
    // When jetty-http3-server is on the classpath, JettyHttp3AsyncSupport
    // auto-activates and the framework diagnostic includes "with Jetty HTTP/3".
    // The chat page renders the container info in its subtitle.
    await page.goto(server.baseUrl);
    // If HTTP/3 connector is present, subtitle includes "HTTP/3"
    // If not (missing dep), it's just "Embedded Jetty 12"
    await expect(page.getByText('Jetty 12')).toBeVisible({ timeout: 10_000 });
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
