import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8093;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Routing E2E', () => {

  test('Code prompts route to code-model', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/routing');
    try {
      await client.connect();
      client.send('Write some code for me');
      await client.waitForDone();

      // RoutingLlmClient sends routing.model metadata before tokens
      const routedModel = client.metadata.get('routing.model');
      expect(routedModel).toBe('code-model');

      // Code model produces tokens starting with "CODE:"
      expect(client.fullResponse).toContain('CODE:');
    } finally {
      client.close();
    }
  });

  test('Translation prompts route to translate-model', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/routing');
    try {
      await client.connect();
      client.send('Please translate this to French');
      await client.waitForDone();

      expect(client.metadata.get('routing.model')).toBe('translate-model');
      expect(client.fullResponse).toContain('TRANSLATE:');
    } finally {
      client.close();
    }
  });

  test('Default routing for unmatched content', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/routing');
    try {
      await client.connect();
      client.send('Tell me a joke');
      await client.waitForDone();

      expect(client.metadata.get('routing.model')).toBe('default-model');
      expect(client.fullResponse).toContain('DEFAULT:');
    } finally {
      client.close();
    }
  });

  test('Concurrent routing: 3 clients send different prompts', async () => {
    const clients = [
      new AiWsClient(server.wsUrl, '/ai/routing'),
      new AiWsClient(server.wsUrl, '/ai/routing'),
      new AiWsClient(server.wsUrl, '/ai/routing'),
    ];

    try {
      for (const c of clients) await c.connect();

      // Send different types of prompts
      clients[0].send('Write code for sorting');
      clients[1].send('Translate hello to Spanish');
      clients[2].send('Tell me about weather');

      // All clients share a broadcaster, so all see all responses.
      // Wait for all 3 complete events (one per prompt/session).
      for (const c of clients) {
        await c.waitForEvents('complete', 3, 20_000);
      }

      // Each client should have received tokens from all 3 models.
      const allTokens = clients[0].fullResponse;
      expect(allTokens).toContain('CODE:');
      expect(allTokens).toContain('TRANSLATE:');
      expect(allTokens).toContain('DEFAULT:');
    } finally {
      clients.forEach(c => c.close());
    }
  });

  test('Routing metadata arrives before first token', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/routing');
    try {
      await client.connect();
      client.send('Help me code a function');
      await client.waitForDone();

      // Find the indices of the first metadata event and first token event
      const metaIdx = client.events.findIndex(
        e => e.type === 'metadata' && e.key === 'routing.model'
      );
      const tokenIdx = client.events.findIndex(e => e.type === 'token');

      expect(metaIdx).toBeGreaterThanOrEqual(0);
      expect(tokenIdx).toBeGreaterThan(metaIdx);
    } finally {
      client.close();
    }
  });
});
