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

  test('Atmosphere Console SPA loads and reaches Connected state', async ({ page }) => {
    // Sample's index.html is a meta-refresh redirect to the bundled Console;
    // navigating to the root pulls in the Vue SPA served by AtmosphereConsoleServlet
    // at /atmosphere/console/* (more specific than AtmosphereServlet's /atmosphere/*,
    // so the SPA wins by Servlet-spec URL pattern resolution).
    await page.goto(server.baseUrl + '/');
    await page.waitForURL(/\/atmosphere\/console\/$/, { timeout: 15_000 });

    // ConnectionStatus.vue flips to "Connected" once atmosphere.js has
    // negotiated the WebSocket handshake against the Console's control-plane
    // SSE endpoint — proving the bundled SPA boots and the runtime is live.
    const statusLabel = page.locator('[data-testid="status-label"]');
    await expect(statusLabel).toHaveText(/Connected/i, { timeout: 30_000 });

    // The chat tab is the default view; assert its layout renders so the
    // SPA actually mounted Vue, not just shipped HTML.
    await expect(page.locator('[data-testid="chat-layout"]')).toBeVisible();
    await expect(page.locator('[data-testid="message-list"]')).toBeVisible();
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

  // Multi-turn / conversation memory.
  //
  // The sample's @AiEndpoint declares `conversationMemory = true`, which wires
  // an InMemoryConversationMemory keyed by AtmosphereResource UUID. Each
  // @Prompt invocation on the same WebSocket should land with the prior
  // turns prepended to the LangChain4j ChatRequest. The structural assertion
  // here is "all three sequential prompts complete cleanly with no error
  // frames" — we deliberately do NOT assert any LLM-content recall ("does
  // the model remember Alice?") because small Ollama models drift; we are
  // testing wire-protocol + memory plumbing, not LLM intelligence. A
  // memory-related regression (e.g. ResourceID-keyed cleanup destroying
  // history mid-conversation) would surface as the second or third prompt
  // erroring out instead of completing.
  test('multi-turn conversation: 3 sequential prompts each complete cleanly',
    async ({}, testInfo) => {
      test.skip(!REAL_LLM, 'Multi-turn memory test requires LLM_MODE=real-ollama');
      testInfo.setTimeout(180_000);

      const wsUrl = server.baseUrl.replace('http', 'ws');
      const client = new AiWsClient(wsUrl, '/atmosphere/ai-chat');
      try {
        await client.connect();

        const prompts = [
          'My name is Alice. Reply briefly.',
          'I live in Paris. Reply briefly.',
          'What did I tell you about myself? Reply briefly.',
        ];

        for (let i = 0; i < prompts.length; i++) {
          client.reset();
          client.send(prompts[i]);
          await client.waitForDone(45_000);

          const errorFrames = client.events.filter(
            (e) => e.type === 'error' || e.event === 'error');
          expect(errorFrames.length,
            `turn ${i + 1} must not surface an error frame; got ${JSON.stringify(errorFrames)}`)
            .toBe(0);

          const completeFrames = client.events.filter(
            (e) => e.type === 'complete' || e.event === 'complete');
          expect(completeFrames.length,
            `turn ${i + 1} expected a terminal complete frame`)
            .toBeGreaterThanOrEqual(1);
        }
      } finally {
        client.close();
      }
    });

  // Disconnect mid-stream / resource cleanup.
  //
  // Open a WS, send a prompt, wait for the first text frame to confirm the
  // L4j stream is mid-flight, then close abruptly. A new client must then be
  // able to connect + chat normally — proving the bridge / broadcaster /
  // streaming-session triple cleans up after a non-cooperative cancel rather
  // than leaking the prior session and corrupting state for new clients.
  // This catches regressions in CompletionTrackingHandler-style wrappers
  // that fail to fire onError/onCompleteResponse on disconnect.
  test('disconnect mid-stream: server recovers and accepts new connections',
    async ({}, testInfo) => {
      test.skip(!REAL_LLM, 'Disconnect-mid-stream test requires LLM_MODE=real-ollama');
      testInfo.setTimeout(120_000);

      const wsUrl = server.baseUrl.replace('http', 'ws');
      const a = new AiWsClient(wsUrl, '/atmosphere/ai-chat');
      try {
        await a.connect();
        a.send('Count slowly from one to twenty.');
        // Wait for the first text frame to confirm we are mid-stream — if we
        // close before the bridge has even started writing, this collapses
        // into a "did the server crash on connect" test instead. 15s is
        // generous to cover a cold qwen2.5:0.5b model load on a CI runner.
        await a.waitForEvents('streaming-text', 1, 15_000);
      } finally {
        a.close();
      }

      // Give the server ~1s to observe the disconnect and clean up the
      // session before we hit it with a fresh client.
      await new Promise((r) => setTimeout(r, 1000));

      const b = new AiWsClient(wsUrl, '/atmosphere/ai-chat');
      try {
        await b.connect();
        b.send('Say hello in one short sentence.');
        await b.waitForDone(45_000);

        const errorFrames = b.events.filter(
          (e) => e.type === 'error' || e.event === 'error');
        expect(errorFrames.length,
          `recovery client must not see error frames; got ${JSON.stringify(errorFrames)}`)
          .toBe(0);

        const completeFrames = b.events.filter(
          (e) => e.type === 'complete' || e.event === 'complete');
        expect(completeFrames.length,
          'recovery client expected a terminal complete frame')
          .toBeGreaterThanOrEqual(1);
      } finally {
        b.close();
      }
    });

  // Multi-client broadcaster fanout.
  //
  // Both clients subscribe to the same Atmosphere broadcaster
  // (/atmosphere/ai-chat). Atmosphere's default DefaultBroadcaster fans out
  // every published frame to every subscribed AtmosphereResource — so when
  // client A's @Prompt fires session.send(...), client B should observe the
  // same streaming-text frames tagged with A's sessionId. This is the
  // canonical channel-style behavior of the framework; if a regression
  // narrows it to per-resource emit (or worse, drops the second subscriber),
  // multi-tenant chat layouts that assume fanout would break silently.
  // The assertion is symmetrical: both clients must see at least one
  // streaming-text frame after A sends a prompt.
  test('broadcaster fanout: second client receives frames from first client\'s prompt',
    async ({}, testInfo) => {
      test.skip(!REAL_LLM, 'Fanout test requires LLM_MODE=real-ollama');
      testInfo.setTimeout(90_000);

      const wsUrl = server.baseUrl.replace('http', 'ws');
      const a = new AiWsClient(wsUrl, '/atmosphere/ai-chat');
      const b = new AiWsClient(wsUrl, '/atmosphere/ai-chat');
      try {
        await a.connect();
        await b.connect();

        a.send('Say hello in one short sentence.');
        // Both clients race to collect frames; A drives the prompt, B
        // observes via broadcaster fanout. We wait on A's terminal frame as
        // the rendezvous, then sample B's collected events.
        await a.waitForDone(45_000);
        // Give B a brief settling window for any in-flight frames.
        await new Promise((r) => setTimeout(r, 500));

        const aText = a.events.filter(
          (e) => e.type === 'streaming-text' || e.event === 'text-delta');
        expect(aText.length,
          'sender (A) must see its own streaming-text frames')
          .toBeGreaterThanOrEqual(1);

        const bText = b.events.filter(
          (e) => e.type === 'streaming-text' || e.event === 'text-delta');
        expect(bText.length,
          'observer (B) must see streaming-text frames via broadcaster fanout — '
          + 'a regression here would break multi-tenant chat broadcasts')
          .toBeGreaterThanOrEqual(1);
      } finally {
        a.close();
        b.close();
      }
    });

  // Long-polling transport coverage.
  //
  // Per Correctness Invariant #7 (Mode Parity), if a feature works over
  // WebSocket it must also work over long-polling. The sample's index.html
  // honors ?transport=long-polling so the test can force atmosphere.js into
  // the fallback transport from a Playwright page.
  //
  // CURRENTLY BLOCKED on atmosphere.js v5 client side — empirically (chrome-
  // devtools, 2026-05-02) the v5 ESM build does not propagate the
  // server-assigned X-Atmosphere-tracking-id between requests on the
  // long-polling and streaming transports. Every POST goes out with
  // tracking-id=0 and the server responds with x-atmosphere-first-request:
  // true, treating each send as a fresh connection rather than a message
  // on the existing subscription — so the prompt body is dropped on the
  // server side. This is independent of the Quarkus L4j bridge (which is
  // transport-agnostic) and reproduces the same way against the existing
  // spring-boot-ai-chat sample. Tracked as a separate atmosphere.js v5
  // issue — restore this assertion once that's fixed.
  test.fixme('long-polling transport: prompt round-trips with same wire envelope',
    async ({ page }, testInfo) => {
      test.skip(!REAL_LLM, 'Long-polling transport test requires LLM_MODE=real-ollama');
      testInfo.setTimeout(90_000);

      await page.goto(server.baseUrl + '/?transport=long-polling');
      await expect(page.locator('#status')).toHaveText(/Connected/i, { timeout: 30_000 });

      await page.locator('#input').fill('Say hello in one short sentence.');
      await page.locator('#send').click();

      const assistant = page.locator('#log .assistant').last();
      await expect(assistant).not.toHaveText(/^< $/, { timeout: 40_000 });
      const text = await assistant.innerText();
      expect(text.length, `expected non-empty assistant response, got: ${JSON.stringify(text)}`)
        .toBeGreaterThan('< '.length);
    });
});
