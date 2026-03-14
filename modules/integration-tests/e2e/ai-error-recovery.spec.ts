import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8100;
let server: AiTestServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Streaming Error Recovery E2E', () => {
  test('mid-stream error halts text delivery and sends error event', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/error-recovery');
    try {
      await client.connect();

      // Prompt triggers error after 2 texts
      client.send('error:2');
      await client.waitForDone(15_000);

      // Should have received an error event
      expect(client.errors.length).toBeGreaterThan(0);
      expect(client.errors[0]).toContain('Simulated error');

      // Should have received some streaming texts before the error
      expect(client.tokens.length).toBe(2);

      // No complete event should have fired
      const completes = client.events.filter(e => e.type === 'complete');
      expect(completes).toHaveLength(0);
    } finally {
      client.close();
    }
  });

  test('error after zero texts: only error event, no partial content', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/error-recovery');
    try {
      await client.connect();

      // Error immediately — no texts emitted
      client.send('error:0');
      await client.waitForDone(15_000);

      expect(client.errors.length).toBeGreaterThan(0);
      expect(client.tokens).toHaveLength(0);
    } finally {
      client.close();
    }
  });

  test('client recovers and succeeds on next prompt after error', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/error-recovery');
    try {
      await client.connect();

      // First prompt causes an error
      client.send('error:1');
      await client.waitForDone(15_000);
      expect(client.errors.length).toBeGreaterThan(0);

      // Reset collected state
      client.reset();

      // Second prompt succeeds
      client.send('normal');
      await client.waitForDone(15_000);

      expect(client.errors).toHaveLength(0);
      expect(client.tokens.length).toBeGreaterThan(0);
      expect(client.fullResponse).toContain('Hello');

      const completes = client.events.filter(e => e.type === 'complete');
      expect(completes).toHaveLength(1);
    } finally {
      client.close();
    }
  });

  test('3 concurrent clients: error on one does not affect others', async () => {
    const errorClient = new AiWsClient(server.wsUrl, '/ai/error-recovery');
    const okClient1 = new AiWsClient(server.wsUrl, '/ai/error-recovery');
    const okClient2 = new AiWsClient(server.wsUrl, '/ai/error-recovery');

    try {
      await Promise.all([errorClient.connect(), okClient1.connect(), okClient2.connect()]);

      // Send prompts — one will error, two will succeed
      errorClient.send('error:1');
      okClient1.send('normal');
      okClient2.send('normal');

      await Promise.all([
        errorClient.waitForDone(15_000),
        okClient1.waitForDone(15_000),
        okClient2.waitForDone(15_000),
      ]);

      // Error client got an error
      expect(errorClient.errors.length).toBeGreaterThan(0);

      // OK clients completed successfully
      expect(okClient1.errors).toHaveLength(0);
      expect(okClient1.tokens.length).toBeGreaterThan(0);
      expect(okClient2.errors).toHaveLength(0);
      expect(okClient2.tokens.length).toBeGreaterThan(0);
    } finally {
      errorClient.close();
      okClient1.close();
      okClient2.close();
    }
  });

  test('metadata (model name) is delivered even when stream errors', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/error-recovery');
    try {
      await client.connect();
      client.send('error:2');
      await client.waitForDone(15_000);

      // FakeLlmClient sends model metadata before streaming
      expect(client.metadata.get('model')).toBe('error-model');
    } finally {
      client.close();
    }
  });
});
