import { test, expect } from '@playwright/test';
import { ChatPage } from './helpers/chat-page';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Auth Rejection', () => {
  test('browser handles 401 on WebSocket upgrade gracefully', async ({ page }) => {
    // Intercept all requests to the Atmosphere endpoint and return 401
    await page.route('**/atmosphere/chat*', async (route) => {
      const request = route.request();
      // Block the initial connection with a 401
      if (request.url().includes('X-Atmosphere-Transport') ||
          request.headers()['upgrade'] === 'websocket') {
        await route.fulfill({
          status: 401,
          contentType: 'text/plain',
          body: 'Unauthorized',
        });
      } else {
        await route.continue();
      }
    });

    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);

    // The chat should NOT show "Connected" — it should show a disconnected
    // or error state, or simply never connect
    await page.waitForTimeout(5000);
    const statusText = await chat.statusLabel.textContent();
    expect(statusText).not.toBe('Connected');
  });

  test('raw WebSocket gets rejected with proper error on invalid upgrade', async () => {
    // Try to connect with an intentionally invalid protocol to test error handling
    const wsUrl = server.baseUrl.replace('http', 'ws') + '/atmosphere/nonexistent';

    const errorPromise = new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('error', (err) => resolve(err.message));
      ws.on('unexpected-response', (_req, res) => {
        resolve(`HTTP ${res.statusCode}`);
      });
      setTimeout(() => resolve('timeout'), 10_000);
    });

    const error = await errorPromise;
    // Should get a non-success response (404, 400, etc.)
    expect(error).not.toBe('timeout');
  });

  test('browser does not enter infinite retry loop on persistent auth failure', async ({ page }) => {
    let requestCount = 0;

    // Block all Atmosphere transport requests with 403
    await page.route('**/atmosphere/chat*', async (route) => {
      const request = route.request();
      if (request.url().includes('X-Atmosphere-Transport') ||
          request.headers()['upgrade'] === 'websocket') {
        requestCount++;
        await route.fulfill({
          status: 403,
          contentType: 'text/plain',
          body: 'Forbidden',
        });
      } else {
        await route.continue();
      }
    });

    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);

    // Wait to observe retry behavior
    await page.waitForTimeout(10_000);

    // The client should have given up or capped retries — not hammering the server
    // atmosphere.js has maxReconnectOnClose: 10 in the chat samples
    expect(requestCount).toBeLessThan(25);
  });
});
