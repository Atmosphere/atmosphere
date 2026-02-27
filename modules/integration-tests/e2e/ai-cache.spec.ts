import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8092;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Cache E2E', () => {

  test('Progress and token events delivered with cache configured', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache');
    try {
      await client.connect();
      client.send('cache-test-progress');
      await client.waitForDone();

      // Progress events should be received
      const progressEvents = client.events.filter(e => e.type === 'progress');
      expect(progressEvents.length).toBeGreaterThan(0);

      // Token events should be received
      expect(client.tokens.length).toBeGreaterThan(0);

      // Complete event should be received
      const completeEvents = client.events.filter(e => e.type === 'complete');
      expect(completeEvents.length).toBe(1);
    } finally {
      client.close();
    }
  });

  test('Two concurrent clients both receive full stream', async () => {
    const clients = [
      new AiWsClient(server.wsUrl, '/ai/cache'),
      new AiWsClient(server.wsUrl, '/ai/cache'),
    ];

    try {
      for (const c of clients) await c.connect();

      // Only one client sends the prompt; both share the broadcaster
      clients[0].send('concurrent-cache-test');

      for (const c of clients) {
        await c.waitForDone();
        // Both clients should receive tokens
        expect(c.tokens.length).toBeGreaterThan(0);
        // Both should have the complete event
        expect(c.events.some(e => e.type === 'complete')).toBe(true);
      }

      // Responses should be identical (same broadcaster)
      expect(clients[0].fullResponse).toBe(clients[1].fullResponse);
    } finally {
      clients.forEach(c => c.close());
    }
  });

  test('Error events delivered with cache configured', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache');
    try {
      await client.connect();
      // "error:" prefix triggers FakeLlmClient.erroring
      client.send('error:test');
      await client.waitForDone();

      // Should have received some tokens before the error
      expect(client.tokens.length).toBeGreaterThan(0);

      // Should have received the error event
      expect(client.errors.length).toBeGreaterThan(0);
    } finally {
      client.close();
    }
  });

  test('Multiple sequential prompts from the same client', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache');
    try {
      await client.connect();

      // First prompt
      client.send('first-prompt');
      await client.waitForDone();
      const firstTokenCount = client.tokens.length;
      expect(firstTokenCount).toBeGreaterThan(0);

      // Reset state for second prompt
      client.reset();

      // Second prompt
      client.send('second-prompt');
      await client.waitForDone();
      expect(client.tokens.length).toBeGreaterThan(0);

      // Both prompts should have worked (cache didn't corrupt state)
    } finally {
      client.close();
    }
  });
});
