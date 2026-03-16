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

test.describe('AiRequest Identity Fields E2E', () => {

  test('identity fields are passed through to AiSupport', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/identity');
    try {
      await client.connect();
      client.send('Hello');
      await client.waitForDone(15_000);

      expect(client.metadata.get('userId')).toBe('user-42');
      expect(client.metadata.get('sessionId')).toBe('sess-abc');
      expect(client.metadata.get('agentId')).toBe('research-agent');
      expect(client.metadata.get('conversationId')).toBe('conv-xyz');
    } finally {
      client.close();
    }
  });

  test('identity fields appear in streamed response', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/identity');
    try {
      await client.connect();
      client.send('Test');
      await client.waitForDone(15_000);

      expect(client.fullResponse).toContain('user-42');
      expect(client.fullResponse).toContain('sess-abc');
      expect(client.fullResponse).toContain('research-agent');
      expect(client.fullResponse).toContain('conv-xyz');
    } finally {
      client.close();
    }
  });

  test('two clients get same identity fields independently', async () => {
    const client1 = new AiWsClient(server.wsUrl, '/ai/identity');
    const client2 = new AiWsClient(server.wsUrl, '/ai/identity');
    try {
      await client1.connect();
      await client2.connect();

      client1.send('From client 1');
      client2.send('From client 2');

      await client1.waitForDone(15_000);
      await client2.waitForDone(15_000);

      // Both should get the same identity (set by handler, not by client)
      expect(client1.metadata.get('userId')).toBe('user-42');
      expect(client2.metadata.get('userId')).toBe('user-42');

      // But different session IDs (different streaming sessions)
      expect(client1.sessionIds.size).toBe(1);
      expect(client2.sessionIds.size).toBe(1);
      const [sid1] = client1.sessionIds;
      const [sid2] = client2.sessionIds;
      expect(sid1).not.toBe(sid2);
    } finally {
      client1.close();
      client2.close();
    }
  });
});
