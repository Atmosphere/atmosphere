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

test.describe('Real LLM — basic chat (Tier 1/2)', () => {
  test('streams at least one text-delta and completes cleanly', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/real/chat');
    try {
      await client.connect();
      client.send('Say hello in one short sentence.');
      await client.waitForDone(30_000);

      const textEvents = client.events.filter(
        (e: { type?: string }) => e.type === 'text-delta' || e.type === 'message');
      expect(textEvents.length,
        `expected >= 1 text-delta frame, got ${client.events.length} events total`)
        .toBeGreaterThanOrEqual(1);

      const errorEvents = client.events.filter((e: { type?: string }) => e.type === 'error');
      expect(errorEvents.length,
        `response must not contain error frames, got ${JSON.stringify(errorEvents)}`)
        .toBe(0);

      const completeEvents = client.events.filter((e: { type?: string }) => e.type === 'complete');
      expect(completeEvents.length, 'expected a complete frame').toBeGreaterThanOrEqual(1);
    } finally {
      client.close();
    }
  });
});
