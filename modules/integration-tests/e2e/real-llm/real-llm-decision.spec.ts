/*
 * Real-LLM e2e test for the decision-model SPI.
 *
 * Runs against a live OpenAI-compatible endpoint (Ollama in CI, keyless). The
 * DecisionModel is whatever the production DecisionModelResolver returns — a
 * RuntimeDecisionModel over the Built-in runtime — and the LLM injection
 * classifier screens two retrieved documents with no rule-based floor, so the
 * surviving set is the model's own verdict.
 *
 * The confidence source is asserted to be one of the two real sources, never
 * assumed to be DECISION_LOGPROBS: whether the endpoint returns top_logprobs is
 * decided by the logprobs allow-list, not by this test.
 *
 * Skipped unless LLM_MODE=real-ollama / real-openai / real-gemini.
 */
import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from '../fixtures/ai-test-server';
import { AiWsClient } from '../helpers/ai-ws-client';
import { llmBudget } from '../helpers/llm-rate-budget';

const PORT = 8198;
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
  if (testInfo.status !== testInfo.expectedStatus && server) {
    // eslint-disable-next-line no-console
    console.log('\n=== AiFeatureTestServer output (tail 4KB) ===');
    // eslint-disable-next-line no-console
    console.log(server.getOutput().slice(-4000));
    // eslint-disable-next-line no-console
    console.log('=== end server output ===\n');
  }
});

test.describe('Real LLM — decision model', () => {
  test.setTimeout(240_000);

  test('typed answer, and the LLM tier drops a literal injection while the benign document survives', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/real/decision');
    try {
      await client.connect();
      await llmBudget();
      client.send('screen');
      await client.waitForDone(200_000);

      expect(client.errors, JSON.stringify(client.errors)).toEqual([]);
      expect(String(client.metadata.get('decision.model'))).toMatch(/^runtime:/);
      expect(client.metadata.get('decision.tier')).toBe('LLM_CLASSIFIER');
      expect(client.metadata.get('decision.type')).toBe('Noul');
      expect(typeof client.metadata.get('decision.value')).toBe('boolean');
      expect(['DECISION_LOGPROBS', 'MODEL_REPORTED_FIELD'])
        .toContain(client.metadata.get('decision.confidence.source'));

      const reply = client.tokens.join('');
      expect(reply).toContain('docs/benign.md');
      expect(reply).not.toContain('docs/injection.md');
    } finally {
      client.close();
    }
  });
});
