import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8099;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI gap-fix E2E coverage', () => {
  test('@smoke RAG pipeline applies query, filter, post-process, and citation hooks', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/gap-coverage');
    try {
      await client.connect();
      client.send('rag');
      await client.waitForDone(15_000);

      expect(client.metadata.get('rag.transformedQuery')).toBe(
        'tenant:alpha atmosphere transports for tenant alpha',
      );
      expect(client.metadata.get('rag.normalizedQuery')).toBe(
        'tenant:alpha atmosphere transports for tenant alpha',
      );
      expect(client.metadata.get('rag.shouldRetrieve')).toBe(true);
      expect(client.metadata.get('rag.retrieved')).toBe(3);
      expect(client.metadata.get('rag.filtered')).toBe(1);
      expect(client.metadata.get('rag.postProcessed')).toBe(1);
      expect(client.metadata.get('rag.postProcessedFlag')).toBe('true');

      const citations = client.metadata.get('rag.citations') as string[];
      expect(citations).toHaveLength(1);
      expect(citations[0]).toContain('transport-guide.md chunk 2/5 chars 40-96 score 0.910');
      expect(client.fullResponse).toContain('WebSocket and SSE fallback');
    } finally {
      client.close();
    }
  });

  test('input assembly telemetry exposes framework-stage token/cost signals', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/gap-coverage');
    try {
      await client.connect();
      client.send('telemetry');
      await client.waitForDone(15_000);

      expect(client.metadata.get('telemetry.tools')).toBe(1);
      expect(client.metadata.get('telemetry.history')).toBe(2);
      expect(client.metadata.get('telemetry.confidenceCue')).toBe(true);
      expect(client.metadata.get('input.system.chars')).toBeGreaterThan(0);
      expect(client.metadata.get('input.tool_schema.chars')).toBeGreaterThan(0);
      expect(client.metadata.get('input.confidence_cue.chars')).toBeGreaterThan(0);
      expect(client.metadata.get('input.scrollback.chars')).toBeGreaterThan(0);
      expect(client.metadata.get('input.user_message.tokens')).toBeGreaterThan(0);
      expect(client.fullResponse).toContain('telemetry ok');
    } finally {
      client.close();
    }
  });

  test('deterministic coordinator evaluator emits pass/fail artifacts on the wire', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/gap-coverage');
    try {
      await client.connect();
      client.send('eval');
      await client.waitForDone(15_000);

      expect(client.metadata.get('eval.name')).toBe('sanity-check');
      expect(client.metadata.get('eval.passed')).toBe(true);
      expect(client.metadata.get('eval.score')).toBe(1);
      expect(client.metadata.get('eval.wordCount')).toBe(7);
      expect(client.metadata.get('eval.reason')).toContain('structured');
      expect(client.fullResponse).toBe('evaluation complete');
    } finally {
      client.close();
    }
  });
});
