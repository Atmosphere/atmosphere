import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

/**
 * E2E tests for the Semantic Kernel AI chat sample.
 *
 * Verifies that the SemanticKernelAgentRuntime SPI is correctly activated,
 * demo-mode streaming works out-of-the-box (no API key required), and the
 * console UI loads properly.
 */

/** Collect JSON frames from a WebSocket until a complete/error frame arrives. */
async function collectFrames(
  wsUrl: string,
  prompt: string,
  timeoutMs = 20_000,
): Promise<Record<string, unknown>[]> {
  return new Promise((resolve, reject) => {
    const frames: Record<string, unknown>[] = [];
    const ws = new WebSocket(wsUrl);
    const timer = setTimeout(() => {
      ws.close();
      reject(new Error(
        `collectFrames timed out after ${timeoutMs}ms (${frames.length} frames)`,
      ));
    }, timeoutMs);

    const finish = () => {
      clearTimeout(timer);
      ws.close();
      resolve(frames);
    };

    ws.on('open', () => ws.send(prompt));
    ws.on('message', (data) => {
      const text = data.toString();
      for (const line of text.split('\n')) {
        const trimmed = line.trim();
        if (!trimmed || trimmed.startsWith('<!--')) continue;
        try {
          const frame = JSON.parse(trimmed) as Record<string, unknown>;
          frames.push(frame);
          const type = frame.type as string | undefined;
          if (type === 'complete' || type === 'error') {
            finish();
            return;
          }
        } catch {
          // Not JSON — Atmosphere handshake UUID frame.
        }
      }
    });
    ws.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

function buildWsUrl(server: SampleServer, path: string): string {
  return server.baseUrl.replace('http', 'ws')
    + path
    + '?X-Atmosphere-Transport=websocket'
    + '&X-Atmosphere-Framework=5.0.0';
}

function metadataValue(
  frames: Record<string, unknown>[],
  key: string,
): unknown {
  const frame = frames.find(
    (f) => f.type === 'metadata' && f.key === key,
  );
  return frame?.value;
}

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-semantic-kernel-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Semantic Kernel Chat — HTTP & Console', () => {
  test('sample starts and responds to HTTP', async () => {
    const res = await fetch(server.baseUrl);
    expect(res.status).toBeLessThan(500);
  });

  test('/atmosphere/console/ returns 200 with HTML', async () => {
    const res = await fetch(`${server.baseUrl}/atmosphere/console/`);
    expect(res.status).toBe(200);
    const contentType = res.headers.get('content-type') ?? '';
    expect(contentType).toContain('text/html');
  });

  test('console loads chat layout', async ({ page }) => {
    await page.goto(`${server.baseUrl}/atmosphere/console/`);
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('console displays Semantic Kernel subtitle', async ({ page }) => {
    await page.goto(`${server.baseUrl}/atmosphere/console/`);
    const body = await page.textContent('body');
    expect(body).toContain('Semantic Kernel');
  });

  test('/.well-known/agent.json returns 404 (no A2A)', async () => {
    const res = await fetch(`${server.baseUrl}/.well-known/agent.json`);
    expect(res.status).toBe(404);
  });
});

test.describe('Semantic Kernel Chat — Demo Mode Streaming', () => {
  test('demo greeting response for "hello"', async () => {
    const url = buildWsUrl(server, '/atmosphere/ai-chat');
    const frames = await collectFrames(url, 'hello');

    // Should have progress frame indicating demo mode
    const progress = frames.find((f) => f.type === 'progress');
    expect(progress).toBeDefined();
    expect(String(progress!.message ?? '')).toContain('Demo mode');
    expect(String(progress!.message ?? '')).toContain('Semantic Kernel');

    // Should have streaming-text frames
    const streaming = frames.filter((f) => f.type === 'streaming-text');
    expect(streaming.length).toBeGreaterThan(0);

    // Should complete without error
    expect(frames.some((f) => f.type === 'complete')).toBe(true);
    expect(frames.some((f) => f.type === 'error')).toBe(false);

    // Complete frame should mention demo mode and SK
    const complete = frames.find((f) => f.type === 'complete');
    expect(String(complete!.text ?? '')).toContain('demo mode');
    expect(String(complete!.text ?? '')).toContain('Semantic Kernel');
  });

  test('demo context-aware response for "atmosphere"', async () => {
    const url = buildWsUrl(server, '/atmosphere/ai-chat');
    const frames = await collectFrames(url, 'Tell me about atmosphere');

    const complete = frames.find((f) => f.type === 'complete');
    expect(complete).toBeDefined();
    const text = String(complete!.text ?? '');
    expect(text).toContain('Semantic Kernel');
    expect(text).toContain('ChatCompletionService');
    expect(text).toContain('WebSocket');
  });

  test('demo context-aware response for "semantic kernel"', async () => {
    const url = buildWsUrl(server, '/atmosphere/ai-chat');
    const frames = await collectFrames(url, 'what is semantic kernel?');

    const complete = frames.find((f) => f.type === 'complete');
    expect(complete).toBeDefined();
    const text = String(complete!.text ?? '');
    expect(text).toContain('ToolCallBehavior');
  });

  test('demo generic response for unrecognized prompt', async () => {
    const url = buildWsUrl(server, '/atmosphere/ai-chat');
    const frames = await collectFrames(url, 'random question ' + Date.now());

    const complete = frames.find((f) => f.type === 'complete');
    expect(complete).toBeDefined();
    const text = String(complete!.text ?? '');
    expect(text).toContain('demo response');
    expect(text).toContain('LLM_API_KEY');
  });

  test('streaming frames arrive word-by-word', async () => {
    const url = buildWsUrl(server, '/atmosphere/ai-chat');
    const frames = await collectFrames(url, 'hello');

    const streaming = frames.filter((f) => f.type === 'streaming-text');
    // DemoResponseProducer splits on whitespace — expect multiple fragments
    expect(streaming.length).toBeGreaterThanOrEqual(5);
  });
});

test.describe('Semantic Kernel Chat — Wire Protocol', () => {
  test('frame sequence follows progress → streaming-text* → complete', async () => {
    const url = buildWsUrl(server, '/atmosphere/ai-chat');
    const frames = await collectFrames(url, 'hello');

    // Filter to typed frames only
    const typed = frames.filter((f) => f.type);
    expect(typed.length).toBeGreaterThanOrEqual(3);

    // First typed frame should be progress
    expect(typed[0].type).toBe('progress');

    // Last typed frame should be complete
    expect(typed[typed.length - 1].type).toBe('complete');

    // Middle frames should be streaming-text
    for (let i = 1; i < typed.length - 1; i++) {
      expect(typed[i].type).toBe('streaming-text');
    }
  });

  test('two parallel clients get independent sessions', async () => {
    const url = buildWsUrl(server, '/atmosphere/ai-chat');

    const [frames1, frames2] = await Promise.all([
      collectFrames(url, 'hello'),
      collectFrames(url, 'what is semantic kernel?'),
    ]);

    const text1 = String(frames1.find((f) => f.type === 'complete')?.text ?? '');
    const text2 = String(frames2.find((f) => f.type === 'complete')?.text ?? '');

    // Responses should be different — session isolation
    expect(text1).not.toBe(text2);
    expect(text1).toContain('demo mode');
    expect(text2).toContain('ToolCallBehavior');
  });
});
