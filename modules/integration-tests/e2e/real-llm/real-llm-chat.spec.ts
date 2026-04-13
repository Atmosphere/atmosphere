/*
 * Real-LLM e2e test — structural assertions only.
 *
 * Runs against a live OpenAI-compatible endpoint (Ollama in Tier-1 CI,
 * OpenAI/Gemini in Tier-2 nightly). Asserts that a simple prompt produces
 * at least one text-delta frame and a clean completion within 30 seconds.
 * No content-based assertions — models drift too fast for stable string
 * matching, and the point of this tier is to catch wire-protocol
 * regressions, not prompt-engineering regressions.
 *
 * Skipped unless LLM_MODE=real-ollama or real-openai is set. CI workflows
 * set LLM_MODE + LLM_BASE_URL + LLM_API_KEY before starting the server.
 */
import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from '../fixtures/ai-test-server';
import { AiWsClient } from '../helpers/ai-ws-client';

const PORT = 8199;
let server: AiTestServer;

const LLM_MODE = process.env.LLM_MODE || 'fake';
const realLlm = LLM_MODE === 'real-ollama' || LLM_MODE === 'real-openai' || LLM_MODE === 'real-gemini';

test.beforeAll(async () => {
  test.skip(!realLlm, 'Real LLM tests require LLM_MODE=real-ollama/real-openai/real-gemini');
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.afterEach(async ({}, testInfo) => {
  // Dump test server stdout/stderr on failure so the Java side's
  // BuiltInAgentRuntime stack traces, progress messages, and HTTP
  // errors land in the Playwright report. Without this the Java
  // exception is invisible and we can only see the TypeScript side
  // of the wire, which turns every real-LLM regression into a guess.
  if (testInfo.status !== testInfo.expectedStatus && server) {
    // eslint-disable-next-line no-console
    console.log('\n=== AiFeatureTestServer output (tail 4KB) ===');
    // eslint-disable-next-line no-console
    console.log(server.getOutput().slice(-4000));
    // eslint-disable-next-line no-console
    console.log('=== end server output ===\n');
  }
});

test.describe('Real LLM — basic chat (Tier 1/2)', () => {
  test('streams at least one text-delta and completes cleanly', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/real/chat');
    try {
      await client.connect();
      client.send('Say hello in one short sentence.');
      await client.waitForDone(30_000);

      // Atmosphere emits text in two wire formats depending on which code
      // path the runtime uses:
      //   - Legacy: {"type":"streaming-text","data":"..."}
      //     — what DefaultStreamingSession.send() emits, used by
      //       OpenAiCompatibleClient (the Built-in runtime's HTTP client).
      //   - AiEvent:  {"event":"text-delta","data":{"text":"..."}}
      //     — what session.emit(new AiEvent.TextDelta(...)) emits.
      // We accept either so the spec survives a future migration.
      const textEvents = client.events.filter(
        (e: { type?: string; event?: string }) =>
          e.type === 'streaming-text' || e.event === 'text-delta');
      expect(textEvents.length,
        `expected >= 1 text frame, got ${client.events.length} events total: `
        + JSON.stringify(client.events.map(e => e.type || e.event)))
        .toBeGreaterThanOrEqual(1);

      const errorEvents = client.events.filter(
        (e: { type?: string; event?: string }) =>
          e.type === 'error' || e.event === 'error');
      expect(errorEvents.length,
        `response must not contain error frames, got ${JSON.stringify(errorEvents)}`)
        .toBe(0);

      const completeEvents = client.events.filter(
        (e: { type?: string; event?: string }) =>
          e.type === 'complete' || e.event === 'complete');
      expect(completeEvents.length, 'expected a complete frame').toBeGreaterThanOrEqual(1);
    } finally {
      client.close();
    }
  });
});
