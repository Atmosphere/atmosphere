import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8091;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Fan-Out E2E', () => {

  test('AllResponses: all 3 models stream to all clients', async () => {
    const clients = [
      new AiWsClient(server.wsUrl, '/ai/fanout'),
      new AiWsClient(server.wsUrl, '/ai/fanout'),
      new AiWsClient(server.wsUrl, '/ai/fanout'),
    ];

    try {
      for (const c of clients) await c.connect();

      clients[0].send('all:Hello');

      for (const c of clients) {
        await c.waitForDone(20_000);

        // Should have received tokens from multiple models
        // Each model produces sessions like "parentId-fast", "parentId-medium", "parentId-slow"
        expect(c.sessionIds.size).toBeGreaterThanOrEqual(3);
      }
    } finally {
      clients.forEach(c => c.close());
    }
  });

  test('Tokens from different models are distinguishable by sessionId', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/fanout');
    try {
      await client.connect();
      client.send('all:Distinguish');

      await client.waitForDone(20_000);

      // Find session IDs that end with model endpoint IDs
      const sessionArray = Array.from(client.sessionIds);
      const fastSessions = sessionArray.filter(id => id.endsWith('-fast'));
      const medSessions = sessionArray.filter(id => id.endsWith('-medium'));
      const slowSessions = sessionArray.filter(id => id.endsWith('-slow'));

      expect(fastSessions.length).toBeGreaterThanOrEqual(1);
      expect(medSessions.length).toBeGreaterThanOrEqual(1);
      expect(slowSessions.length).toBeGreaterThanOrEqual(1);
    } finally {
      client.close();
    }
  });

  test('FirstComplete: fastest model wins', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/fanout');
    try {
      await client.connect();
      client.send('first:Race');

      // fanout.complete metadata arrives after child sessions complete
      await client.waitForMetadata('fanout.complete', 15_000);
      expect(client.metadata.get('fanout.complete')).toBe(true);

      // Fast model (30ms/token) should produce tokens; slow model (200ms) may be cancelled
      const tokenEvents = client.events.filter(e => e.type === 'token');
      expect(tokenEvents.length).toBeGreaterThan(0);
    } finally {
      client.close();
    }
  });

  test('FastestTokens: producer wins after threshold', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/fanout');
    try {
      await client.connect();
      // threshold=3 means the fastest model producing 3 tokens wins
      client.send('fastest:3:SpeedTest');

      // fanout.complete metadata arrives after strategy completes
      await client.waitForMetadata('fanout.complete', 15_000);
      expect(client.metadata.get('fanout.complete')).toBe(true);
      expect(client.tokens.length).toBeGreaterThan(0);
    } finally {
      client.close();
    }
  });

  test('3 simultaneous prompts from 3 clients', async () => {
    const clients = [
      new AiWsClient(server.wsUrl, '/ai/fanout'),
      new AiWsClient(server.wsUrl, '/ai/fanout'),
      new AiWsClient(server.wsUrl, '/ai/fanout'),
    ];

    try {
      for (const c of clients) await c.connect();

      // All 3 clients send prompts simultaneously
      clients[0].send('all:Prompt1');
      clients[1].send('all:Prompt2');
      clients[2].send('all:Prompt3');

      // All should complete (since they share a broadcaster, all see all broadcasts)
      for (const c of clients) {
        await c.waitForDone(20_000);
        expect(c.tokens.length).toBeGreaterThan(0);
      }
    } finally {
      clients.forEach(c => c.close());
    }
  });
});
