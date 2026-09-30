/*
 * Real-LLM e2e test for the decision-model SPI.
 *
 * Runs against a live OpenAI-compatible endpoint (Ollama in CI, keyless). The
 * DecisionModel is whatever the production DecisionModelResolver returns — a
 * RuntimeDecisionModel over the Built-in runtime — and the LLM injection
 * classifier screens two retrieved documents with no rule-based floor. The
 * handler reports the tier's own outcome per document, so the injection must be
 * INJECTED — a timeout, unparseable reply or uncertain band is an ERROR, which
 * the default policy would drop just the same and must not pass for detection.
 *
 * On Ollama the confidence source must be DECISION_LOGPROBS: its
 * chat-completions surface returns top_logprobs (observed on Ollama 0.34.4 with
 * qwen2.5:1.5b at temperature 0). On other endpoints either real source is
 * accepted, since whether they return top_logprobs is decided by the logprobs
 * allow-list, not by this test.
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

  test('typed answer, and the LLM tier convicts a literal injection while it clears the benign document', async () => {
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
      // The benign text says the museum is open every day, Sunday included.
      expect(client.metadata.get('decision.value')).toBe(true);
      if (LLM_MODE === 'real-ollama') {
        // Ollama's chat-completions surface returns top_logprobs (observed on
        // 0.34.4 with qwen2.5:1.5b), so the decision must be measured there.
        expect(client.metadata.get('decision.confidence.source')).toBe('DECISION_LOGPROBS');
      } else {
        expect(['DECISION_LOGPROBS', 'MODEL_REPORTED_FIELD'])
          .toContain(client.metadata.get('decision.confidence.source'));
      }

      // Recorded in the run log: which confidence source this endpoint and
      // model actually produced, and each document's verdict and reason.
      // eslint-disable-next-line no-console
      console.log(`decision.confidence.source=${client.metadata.get('decision.confidence.source')} `
        + `injection=${client.metadata.get('decision.reason.docs/injection.md')} `
        + `benign=${client.metadata.get('decision.reason.docs/benign.md')}`);
      const injectionVerdict = client.metadata.get('decision.verdict.docs/injection.md');
      expect(injectionVerdict, String(client.metadata.get('decision.reason.docs/injection.md')))
        .toBe('INJECTED');
      expect(client.metadata.get('decision.verdict.docs/benign.md'),
        String(client.metadata.get('decision.reason.docs/benign.md'))).toBe('SAFE');

      const reply = client.tokens.join('');
      expect(reply).toContain('docs/benign.md');
      expect(reply).not.toContain('docs/injection.md');
    } finally {
      client.close();
    }
  });
});
