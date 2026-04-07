import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';
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

  test('HTTP/3 QUIC port is listening on UDP 4443', async () => {
    // Verify the QUIC connector started by checking the UDP port.
    // lsof returns non-zero if the port is not bound.
    const result = execSync(
      `lsof -i UDP:4443 -P 2>/dev/null | grep -c QUIC || echo 0`,
      { encoding: 'utf-8' }
    ).trim();
    const count = parseInt(result, 10);
    expect(count).toBeGreaterThan(0);
  });

  test('server logs confirm JettyHttp3AsyncSupport is active', async () => {
    // The sample's stdout/stderr should contain the Atmosphere diagnostic line
    // mentioning JettyHttp3AsyncSupport. Check the server's log output.
    const logs = server.stdout ?? '';
    expect(logs).toContain('JettyHttp3AsyncSupport');
  });

  test('chat works over WebSocket while HTTP/3 connector is active', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('Http3Tester');
    await chat.sendMessage('HTTP/3 connector is live on QUIC!');
    await chat.expectMessage('HTTP/3 connector is live on QUIC!');
  });
});
