import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(180_000);
  server = await startSample(SAMPLES['spring-boot-multi-agent-startup-team']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Send a message via WebSocket and collect the streamed response. */
function sendAndCollect(
  baseUrl: string,
  path: string,
  message: string,
  timeoutMs = 30_000,
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

test.describe('Coordinator Journal', () => {

  test('coordinator registers agents at startup', async () => {
    const output = server.getOutput();
    expect(output).toContain('registered');
  });

  test('task execution produces coordinator response', async () => {
    const result = await sendAndCollect(
      server.baseUrl, '/atmosphere/agent/ceo', 'Analyze this topic for journaling test', 20_000,
    );
    expect(result.texts.length).toBeGreaterThan(0);
  });

  test('sequential task executions complete independently', async () => {
    for (let i = 0; i < 3; i++) {
      const result = await sendAndCollect(
        server.baseUrl, '/atmosphere/agent/ceo', `Sequential journal task ${i}`, 20_000,
      );
      expect(result.texts.length).toBeGreaterThan(0);
    }
  });
});
