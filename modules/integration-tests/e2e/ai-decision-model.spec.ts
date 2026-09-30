import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8121;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * E2E coverage for the LLM_CLASSIFIER injection tier over a DecisionModel
 * (DecisionModelTestHandler). A RAG turn runs the real AiStreamingSession
 * retrieval path: SafetyContextProvider screens three retrieved documents with
 * LlmClassifierInjectionClassifier over a RuntimeDecisionModel whose runtime
 * reports a DECISION_LOGPROBS distribution per document, and the echo runtime
 * answers with the augmented prompt — so the reply shows exactly which
 * documents reached the model.
 *
 * P(injection): benign 0.03 (safe), paraphrased injection 0.97 (injected),
 * educational quote 0.35 (uncertain band -> error -> dropped, fail-closed).
 * The paraphrase matches no rule-based probe: only the decision model catches it.
 */
test.describe('AI Decision Model — LLM injection tier E2E', () => {

  test('@smoke DROP keeps the benign document and drops the injection and the uncertain one', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/decision');
    try {
      await client.connect();
      client.send('drop');
      await client.waitForDone(15_000);

      const reply = client.tokens.join('');
      expect(client.errors).toEqual([]);
      expect(reply).toContain('docs/benign.md');
      expect(reply).toContain('Our store opens at 9am');
      expect(reply).not.toContain('docs/paraphrased-injection.md');
      expect(reply).not.toContain('attacker@example.com');
      expect(reply).not.toContain('docs/educational.md');
      expect(client.metadata.get('rag.safety.flagged')).toBe('');
    } finally {
      client.close();
    }
  });

  test('FLAG keeps the injection marked as flagged; the uncertain document is still dropped', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/decision');
    try {
      await client.connect();
      client.send('flag');
      await client.waitForDone(15_000);

      const reply = client.tokens.join('');
      expect(reply).toContain('docs/benign.md');
      expect(reply).toContain('docs/paraphrased-injection.md');
      expect(client.metadata.get('rag.safety.flagged')).toBe('docs/paraphrased-injection.md');
      // An uncertain verdict is an error, not an injection: FLAG does not apply,
      // the fail-closed error path drops it.
      expect(reply).not.toContain('docs/educational.md');
    } finally {
      client.close();
    }
  });
});
