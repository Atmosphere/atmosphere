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

  // Live console coverage for this sample is not duplicated here: single-turn
  // send/receive/input-clears lives in unified-console.spec.ts and the
  // multi-turn transcript case in ai-chat-features.spec.ts, both driving this
  // same sample through the same console with the `?token=` param. What
  // follows is the wire-level @AiEndpoint behaviour unique to this spec.

  // Gap #7a — @AiEndpoint(promptCache = CONSERVATIVE) end-to-end.
  //
  // PromptCacheDemoChat instantiates a real AiPipeline + InMemoryResponseCache
  // backed by an inline AgentRuntime and routes every prompt through
  // AiPipeline.execute(...). The framework's cache gate emits ai.cache.hit on
  // the wire — false on the first request (cache miss, runtime fires and
  // stores) and true on the second request with the same prompt (cache hits,
  // runtime skipped, cached text replayed). This is the canonical
  // framework-level wire signal — no sample-level shim involved.
  test('@AiEndpoint(promptCache) surfaces cache-hit on repeated prompt', async () => {
    const url = buildWsUrl(server, '/atmosphere/ai-chat-with-cache');
    const prompt = 'cache-hit-check-' + Date.now();

    const firstFrames = await collectFrames(url, prompt);
    expect(metadataValue(firstFrames, 'prompt.cache.policy')).toBe('CONSERVATIVE');
    // Runtime truth: the sample reports which cache the framework seam actually
    // resolved. org.atmosphere.ai.cache.semantic is off here (and no embedding
    // backend runs in this lane), so it must report the exact cache rather than
    // claiming semantic matching it cannot perform.
    expect(metadataValue(firstFrames, 'prompt.cache.kind')).toBe('exact');
    expect(metadataValue(firstFrames, 'ai.cache.hit')).toBe(false);
    expect(firstFrames.some((f) => f.type === 'error')).toBe(false);
    expect(firstFrames.some((f) => f.type === 'complete')).toBe(true);

    const secondFrames = await collectFrames(url, prompt);
    expect(metadataValue(secondFrames, 'prompt.cache.policy')).toBe('CONSERVATIVE');
    expect(metadataValue(secondFrames, 'ai.cache.hit')).toBe(true);
    expect(secondFrames.some((f) => f.type === 'error')).toBe(false);
    expect(secondFrames.some((f) => f.type === 'complete')).toBe(true);
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

  // Gap #10c — multi-modal @Agent.
  //
  // MultiModalAgent is an @Agent class (registered at /atmosphere/agent/multimodal)
  // that accepts "image:<base64>" prompts, decodes them, and emits a
  // Content.Image frame on the AiStreamingSession bound to the same
  // AtmosphereResource. The test uploads a 1x1 PNG constant, asserts the
  // sample echoed its metadata, and verifies at least one streaming text
  // frame arrives with no error frame.
  test('@Agent accepts base64 image upload and streams text reply', async () => {
    const url = buildWsUrl(server, '/atmosphere/agent/multimodal');
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

  // Dev inspector — atmosphere.ai.dev-inspector.enabled=true in this sample's
  // application.yml installs the bounded in-memory recorder, which the shared
  // DispatchDecorators chain wraps around every turn on both dispatch paths.
  //
  // Asserts the feature RECORDS, not that the app booted: drive a unique prompt
  // through the pipeline endpoint, then read it back from the admin surface and
  // match the prompt preview to the exact text that was just sent.
  test('dev inspector records the turn that was just dispatched', async () => {
    const prompt = 'dev-inspector-check-' + Date.now();
    const frames = await collectFrames(
      buildWsUrl(server, '/atmosphere/ai-chat-with-cache'), prompt);
    expect(frames.some((f) => f.type === 'complete')).toBe(true);

    type Entry = { promptPreview: string; responsePreview: string; status: string };

    // The recorder is written on the terminal event, so poll briefly.
    let recorded: Entry | undefined;
    await expect.poll(async () => {
      const res = await fetch(`${server.baseUrl}/api/admin/ai/dev/inspector?limit=50`, {
        headers: { 'X-Atmosphere-Auth': 'demo-token' },
      });
      if (!res.ok) return false;
      const entries = (await res.json()) as Entry[];
      recorded = entries.find((e) => (e.promptPreview ?? '').includes(prompt));
      return recorded !== undefined;
    }, { timeout: 15_000, intervals: [250] }).toBe(true);

    expect(recorded!.status).toBe('OK');
    expect(recorded!.responsePreview).toContain('Cached response for');
  });

  // The inspector retains prompt AND response previews, so its read sits behind
  // the recorded-content auth gate (Correctness Invariant #6) exactly like
  // /tape/runs and /governance/decisions — enabling it in a sample must not
  // open prompt content to anonymous callers.
  test('dev inspector read is denied without a token', async () => {
    const res = await fetch(`${server.baseUrl}/api/admin/ai/dev/inspector`);
    expect(res.status).toBe(401);
  });
});
