import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8095;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Cache Coalescing E2E', () => {

  test('Coalesced event fires once with correct token count', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache-coalescing');
    try {
      await client.connect();
      client.send('coalescing-test');
      await client.waitForDone();

      // Should receive exactly 5 tokens from FakeLlmClient.slow
      expect(client.tokens.length).toBe(5);

      // Coalescing metadata should have been sent
      expect(client.metadata.get('coalescing.enabled')).toBe(true);

      // Complete event should fire exactly once
      const completeEvents = client.events.filter(e => e.type === 'complete');
      expect(completeEvents.length).toBe(1);

      // Verify server output contains the COALESCED log line
      const output = server.getOutput();
      expect(output).toContain('COALESCED:');
      expect(output).toContain(':tokens=5:');
      expect(output).toContain(':status=complete:');
    } finally {
      client.close();
    }
  });

  test('Coalesced event includes elapsed time', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache-coalescing');
    try {
      await client.connect();
      client.send('elapsed-test');
      await client.waitForDone();

      // Verify server output contains elapsed time >= 0
      const output = server.getOutput();
      const match = output.match(/:elapsed=(\d+)/);
      expect(match).toBeTruthy();
      const elapsed = parseInt(match![1], 10);
      expect(elapsed).toBeGreaterThanOrEqual(0);
    } finally {
      client.close();
    }
  });
});
