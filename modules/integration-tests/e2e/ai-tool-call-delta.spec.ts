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

test.describe('toolCallDelta Incremental Streaming (Wave 3)', () => {

  test('@smoke delta chunks arrive before tool-start and concatenate to valid JSON', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/tool-call-delta');
    try {
      await client.connect();
      client.send('stream');
      await client.waitForDone(15_000);

      // Delta chunks arrive as metadata events keyed by "ai.toolCall.delta.tc_001"
      const deltaEvents = client.events.filter(
        e => e.type === 'metadata' && e.key?.startsWith('ai.toolCall.delta.')
      );
      expect(deltaEvents.length).toBeGreaterThanOrEqual(3);

      // Concatenated chunks must form valid JSON
      const concatenated = deltaEvents.map(e => e.value as string).join('');
      const parsed = JSON.parse(concatenated);
      expect(parsed.city).toBe('Montreal');

      // tool-start must arrive after all deltas
      const toolStart = client.events.find(e => e.event === 'tool-start');
      expect(toolStart).toBeDefined();

      const lastDeltaIdx = client.events.lastIndexOf(deltaEvents[deltaEvents.length - 1]);
      const toolStartIdx = client.events.indexOf(toolStart!);
      expect(toolStartIdx).toBeGreaterThan(lastDeltaIdx);

      // tool-result must follow
      const toolResult = client.aiEventData('tool-result');
      expect(toolResult).toBeDefined();
      expect((toolResult!.result as Record<string, unknown>).temp).toBe(22);
    } finally {
      client.close();
    }
  });
});
