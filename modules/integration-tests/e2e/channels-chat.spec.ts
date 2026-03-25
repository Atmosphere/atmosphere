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

test.describe('Channels Chat', () => {
  // -- Endpoint registration --

  test('AI endpoint accepts connections at /atmosphere/ai-chat', async () => {
    const res = await fetch(`${server.baseUrl}/atmosphere/ai-chat`);
    expect(res.status).not.toBe(404);
  });

  test('server started successfully', () => {
    const output = server.getOutput();
    expect(output).toContain('Starting service [Tomcat]');
  });

  // -- WebSocket streaming (demo mode) --

  test('streams a response to a message', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/ai-chat', 'hello', 15_000);
    expect(result.texts.length).toBeGreaterThan(0);
    expect(result.fullText.length).toBeGreaterThan(10);
  });

  // -- Console UI --

  test('console page loads', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
  });
});
