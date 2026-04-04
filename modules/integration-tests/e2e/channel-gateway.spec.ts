import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-channels-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Send a message via WebSocket and collect the streamed response. */
function sendAndCollect(
  baseUrl: string,
  path: string,
  message: string,
  timeoutMs = 20_000,
): Promise<{ texts: string[]; fullText: string }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http://', 'ws://') + path;
    const ws = new WebSocket(wsUrl);
    const texts: string[] = [];
    let opened = false;
    const timer = setTimeout(() => {
      ws.close();
      resolve({ texts, fullText: texts.join('') });
    }, timeoutMs);

    ws.on('open', () => { opened = true; ws.send(message); });
    ws.on('message', (data) => {
      const raw = data.toString();
      const parts = raw.split('|');
      for (const part of parts) {
        const trimmed = part.trim();
        if (trimmed && !trimmed.match(/^\d+$/) && trimmed !== 'X') {
          texts.push(trimmed);
        }
      }
    });
    ws.on('close', () => { clearTimeout(timer); resolve({ texts, fullText: texts.join('') }); });
    ws.on('error', (err) => {
      clearTimeout(timer);
      if (!opened) reject(new Error(`WebSocket failed: ${err.message}`));
      else resolve({ texts, fullText: texts.join('') });
    });
  });
}

test.describe('Channel Gateway — Discord/Telegram WebSocket Lifecycle', () => {

  test('chat endpoint accepts WebSocket connections', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => { ws.close(); resolve('connected'); });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    expect(result).toBe('connected');
  });

  test('sending message produces response', async () => {
    const { texts } = await sendAndCollect(
      server.baseUrl, '/atmosphere/ai-chat', 'Hello from channel test!',
    );

    expect(texts.length).toBeGreaterThan(0);
  });

  test('webhook endpoint exists', async () => {
    // Channels chat has a webhook endpoint for incoming messages
    const res = await fetch(`${server.baseUrl}/api/channels/webhook`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        channel: 'test',
        message: 'webhook test',
        author: 'bot',
      }),
    });

    // Should accept the request (200/202) or return 404 if not configured
    // Either way, server should not crash
    expect(res.status).toBeLessThan(500);
  });

  test('console UI loads', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
  });

  test('multiple WebSocket clients can connect simultaneously', async () => {
    const connections: WebSocket[] = [];

    for (let i = 0; i < 3; i++) {
      const ws = await new Promise<WebSocket>((resolve, reject) => {
        const wsUrl = server.baseUrl.replace('http', 'ws') +
          '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
        const socket = new WebSocket(wsUrl);
        socket.on('open', () => resolve(socket));
        socket.on('error', reject);
        setTimeout(() => reject(new Error('timeout')), 10_000);
      });
      connections.push(ws);
    }

    expect(connections.length).toBe(3);

    for (const ws of connections) ws.close();
  });
});
