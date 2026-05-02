import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer, type SampleConfig } from './fixtures/sample-server';
import { AiWsClient } from './helpers/ai-ws-client';

/**
 * E2E coverage for `samples/quarkus-ai-chat` and the underlying
 * `atmosphere-quarkus-langchain4j` bridge module.
 *
 * The sample boots Quarkus 3.31 + Quarkus LangChain4j; the bridge looks up the
 * CDI synthetic `StreamingChatModel` produced by Quarkus L4j and installs it
 * on `LangChain4jAgentRuntime` at startup. This spec verifies:
 *
 *   1. The sample starts (Quarkus boot + Atmosphere servlet init).
 *   2. `/api/admin/runtimes/active` reports `langchain4j` — proof that the
 *      AgentRuntime SPI resolved the bridge-installed runtime, not the demo
 *      fallback that fires when no chat model is registered.
 *   3. The chat UI loads and atmosphere.js negotiates a transport to the
 *      `@AiEndpoint` over WebSocket — i.e. the Quarkus extension scanned
 *      the `@AiEndpoint` annotation and registered the AI servlet path.
 *   4. (Tier-1 real-LLM only) A prompt sent over WebSocket produces at least
 *      one `streaming-text` frame, ends with a `complete` frame, and emits no
 *      `error` frames — proving the bridge actually streams tokens through
 *      Quarkus L4j → `LangChain4jAgentRuntime` → Atmosphere broadcaster
 *      → WebSocket end-to-end against a real provider.
 *
 * The streaming test is skipped unless `LLM_MODE=real-ollama` is set — the
 * default boot uses `dummy` for the API key (enough for the synthetic
 * StreamingChatModel bean to materialise so the bridge can wire it), but a
 * real `chat/completions` call would 401 at the upstream. CI workflow
 * `e2e-real-llm.yml` sets the Ollama env vars and brings up a local Ollama
 * service container before running this project.
 */

const REAL_LLM = process.env.LLM_MODE === 'real-ollama'
  || process.env.LLM_MODE === 'real-openai'
  || process.env.LLM_MODE === 'real-gemini';

/**
 * In real-LLM mode, propagate LLM_BASE_URL / LLM_MODEL / LLM_API_KEY into the
 * spawned Quarkus JVM so application.properties' `${LLM_*:default}`
 * substitution picks the live provider instead of the dummy default.
 */
function buildSampleConfig(): SampleConfig {
  const base = SAMPLES['quarkus-ai-chat'];
  if (!REAL_LLM) return base;
  return {
    ...base,
    env: {
      ...base.env,
      ...(process.env.LLM_BASE_URL ? { LLM_BASE_URL: process.env.LLM_BASE_URL } : {}),
      ...(process.env.LLM_MODEL ? { LLM_MODEL: process.env.LLM_MODEL } : {}),
      ...(process.env.LLM_API_KEY ? { LLM_API_KEY: process.env.LLM_API_KEY } : {}),
    },
  };
}

let server: SampleServer;

// startSample's port + http + websocket probes add up to ~135s on a slow
// runner; bump the hook timeout above Playwright's 90s default so a CI
// cold start does not get truncated mid-await.
test.beforeAll(async ({}, testInfo) => {
  testInfo.setTimeout(180_000);
  server = await startSample(buildSampleConfig());
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Quarkus AI Chat', () => {
  test('runtimes/active reports langchain4j (bridge wired the StreamingChatModel)',
    async ({ request }) => {
      const res = await request.get(
        `${server.baseUrl}/api/admin/runtimes/active`,
      );
      expect(res.ok()).toBeTruthy();

      const active = await res.json();
      // If the bridge had failed, AgentRuntimeResolver would have fallen
      // back to the built-in demo runtime here; asserting "langchain4j"
      // pins the bridge to the critical path.
      expect(active.name).toBe('langchain4j');
      expect(active.isAvailable).toBe(true);
      expect(Array.isArray(active.capabilities)).toBeTruthy();
      expect(active.capabilities).toContain('TEXT_STREAMING');
    });

  test('chat UI loads and reaches Connected state', async ({ page }) => {
    await page.goto(server.baseUrl + '/');

    // The status div flips to "Connected" once atmosphere.js has finished
    // negotiating the WebSocket handshake against /atmosphere/ai-chat,
    // proving the @AiEndpoint scan in atmosphere-quarkus-extension picked
    // up the AiChat handler and registered the servlet path.
    const status = page.locator('#status');
    await expect(status).toHaveText(/Connected/i, { timeout: 30_000 });
    await expect(status).toHaveAttribute('data-state', 'Connected');

    // The Send button is initially disabled (status !== Connected); after
    // the handshake it must be enabled. Visible-and-enabled is enough to
    // confirm the page is interactive.
    await expect(page.getByRole('button', { name: /send/i })).toBeEnabled();
  });

  // Real-LLM streaming test — runs only when LLM_MODE=real-ollama (or the
  // paid-tier real-openai/real-gemini equivalents). Default unit-test boot
  // uses `dummy` for the API key, which is fine for admission tests above
  // but cannot produce streaming-text frames — a real `chat/completions`
  // call would 401 at the upstream. The CI workflow `e2e-real-llm.yml`
  // exports LLM_MODE + LLM_BASE_URL + LLM_MODEL + LLM_API_KEY before
  // launching this project so the prompt round-trips against a live
  // provider, asserting the wire-level streaming pipeline works through
  // the bridge end-to-end.
  test('streams text deltas through bridge → broadcaster → WebSocket against real LLM',
    async ({}, testInfo) => {
      test.skip(!REAL_LLM,
        'Real LLM streaming test requires LLM_MODE=real-ollama (or paid-tier equivalent)');
      testInfo.setTimeout(60_000);

      const wsUrl = server.baseUrl.replace('http', 'ws');
      const client = new AiWsClient(wsUrl, '/atmosphere/ai-chat');
      try {
        await client.connect();
        client.send('Say hello in one short sentence.');
        await client.waitForDone(45_000);

        // streaming-text is the legacy DefaultStreamingSession.send() format;
        // text-delta is the newer AiEvent.TextDelta format. Either path is
        // acceptable — what we are asserting is that *some* token arrived
        // through the bridge, not which serialization shape it used.
        const textFrames = client.events.filter(
          (e) => e.type === 'streaming-text' || e.event === 'text-delta');
        expect(textFrames.length,
          'expected >= 1 text frame from bridge — got '
          + JSON.stringify(client.events.map(e => e.type || e.event)))
          .toBeGreaterThanOrEqual(1);

        const errorFrames = client.events.filter(
          (e) => e.type === 'error' || e.event === 'error');
        expect(errorFrames.length,
          `bridge must not surface error frames; got ${JSON.stringify(errorFrames)}`)
          .toBe(0);

        const completeFrames = client.events.filter(
          (e) => e.type === 'complete' || e.event === 'complete');
        expect(completeFrames.length,
          'expected a terminal complete frame after the response')
          .toBeGreaterThanOrEqual(1);
      } finally {
        client.close();
      }
    });
});
