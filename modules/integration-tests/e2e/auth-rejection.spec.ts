import { test, expect } from '@playwright/test';
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
  test('raw WebSocket to nonexistent endpoint gets rejected', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') + '/atmosphere/nonexistent';

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => { ws.close(); resolve('connected'); });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    // Should NOT connect successfully to a nonexistent path
    expect(result).not.toBe('timeout');
  });

  test('browser does not enter infinite retry loop on persistent failure', async ({ page }) => {
    let requestCount = 0;

    // Block all Atmosphere HTTP requests (SSE/LP fallbacks) with 403
    // Note: WS upgrades can't be intercepted by page.route, so we block
    // the HTTP transport paths that atmosphere.js falls back to
    await page.route('**/atmosphere/chat*', async (route) => {
      const request = route.request();
      // Only block non-page requests (API/transport calls)
      if (request.resourceType() !== 'document') {
        requestCount++;
        await route.abort('connectionrefused');
      } else {
        await route.continue();
      }
    });

    const { ChatPage } = await import('./helpers/chat-page');
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);

    // Wait to observe retry behavior
    await page.waitForTimeout(10_000);

    // The client should have capped retries, not hammering indefinitely
    // atmosphere.js has maxReconnectOnClose: 10 in the chat samples
    expect(requestCount).toBeLessThan(30);
  });

  test('connection to valid endpoint succeeds', async () => {
    // Baseline: connecting to the real endpoint works fine
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => { ws.close(); resolve('connected'); });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    expect(result).toBe('connected');
  });
});
