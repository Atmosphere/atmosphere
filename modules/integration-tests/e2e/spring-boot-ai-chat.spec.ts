import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

// 1x1 transparent PNG (68 bytes) — base64 constant so the spec does not
// depend on any filesystem asset or native image library.
const TINY_PNG_B64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Z12' +
  'l4sAAAAASUVORK5CYII=';

/** Collect JSON frames from a WebSocket until a complete/error frame arrives. */
async function collectFrames(
  wsUrl: string,
  prompt: string,
  timeoutMs = 15_000,
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
          // Not JSON — probably the Atmosphere handshake UUID frame.
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
  // Intentionally omit X-Atmosphere-TrackMessageSize — when enabled, the
  // server prefixes every frame with a "<bytes>|" length header which
  // confuses a plain JSON.parse split on newlines.
  return server.baseUrl.replace('http', 'ws')
    + path
    + '?X-Atmosphere-Transport=websocket'
    + '&X-Atmosphere-Framework=5.0.0'
    + '&X-Atmosphere-Auth=demo-token';
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
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring Boot AI Chat', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  // Auth-gated sample: console WebSocket blocked without token.
  // Full auth flow tested in auth-token.spec.ts (raw WebSocket with X-Atmosphere-Auth).
  test.skip('user receives a response after sending a message', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('message-list')).toContainText('demo mode', { timeout: 30_000 });
  });

  // Auth-gated: console can't connect without token
  test.skip('user can send a prompt and receive streaming response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('message-list')).toContainText('Atmosphere', { timeout: 30_000 });
  });

  // Auth-gated: console can't connect without token
  test.skip('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  // Auth-gated: console can't connect without token
  test.skip('multi-turn conversation preserves history', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');

    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('message-list')).toContainText('demo mode', { timeout: 30_000 });

    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('Hello', { exact: true })).toBeVisible();
    await expect(page.getByText('What is Atmosphere?')).toBeVisible();
  });

  // Gap #7b — @AiEndpoint(retry = @Retry(...)) end-to-end with
  // deterministic fault injection.
  //
  // RetryDemoChat echoes its annotation-declared retry attributes and
  // exposes a per-id attempt counter via "retry.attempt". The first
  // "fail-once:<id>" prompt errors (attempt=1); a second request with the
  // same id succeeds (attempt=2). Mirrors ai-retry-policy.spec.ts's echo
  // pattern.
  test('@AiEndpoint(retry=@Retry) echoes policy and recovers after fault injection', async () => {
    const url = buildWsUrl(server, '/atmosphere/ai-chat-with-retry');
    const id = 'retry-check-' + Date.now();

    const firstFrames = await collectFrames(url, `fail-once:${id}`);
    expect(metadataValue(firstFrames, 'retry.maxRetries')).toBe(2);
    expect(metadataValue(firstFrames, 'retry.initialDelayMs')).toBe(100);
    expect(metadataValue(firstFrames, 'retry.maxDelayMs')).toBe(500);
    expect(metadataValue(firstFrames, 'retry.backoffMultiplier')).toBe(2.0);
    expect(metadataValue(firstFrames, 'retry.attempt')).toBe(1);
    expect(firstFrames.some((f) => f.type === 'error')).toBe(true);

    const secondFrames = await collectFrames(url, `fail-once:${id}`);
    expect(metadataValue(secondFrames, 'retry.attempt')).toBe(2);
    expect(secondFrames.some((f) => f.type === 'error')).toBe(false);
    expect(secondFrames.some((f) => f.type === 'complete')).toBe(true);
  });

  // Gap #10c — multi-modal @AiEndpoint.
  //
  // MultiModalChat accepts "image:<base64>" prompts, decodes them, and
  // emits a Content.Image frame on a DefaultStreamingSession bound to the
  // same AtmosphereResource. The test uploads a 1x1 PNG constant, asserts
  // the sample echoed its metadata, and verifies at least one streaming
  // text frame arrives with no error frame.
  test('@AiEndpoint accepts base64 image upload and streams text reply', async () => {
    const url = buildWsUrl(server, '/atmosphere/ai-chat-multimodal');
    const frames = await collectFrames(url, `image:image/png:${TINY_PNG_B64}`);

    expect(metadataValue(frames, 'multimodal.accepted')).toBe(true);
    expect(metadataValue(frames, 'multimodal.mimeType')).toBe('image/png');
    const byteCount = metadataValue(frames, 'multimodal.bytes');
    expect(typeof byteCount).toBe('number');
    expect(byteCount as number).toBeGreaterThan(0);

    const streamingText = frames.filter((f) => f.type === 'streaming-text');
    expect(streamingText.length).toBeGreaterThan(0);
    expect(frames.some((f) => f.type === 'error')).toBe(false);
    expect(frames.some((f) => f.type === 'complete')).toBe(true);
  });
});
