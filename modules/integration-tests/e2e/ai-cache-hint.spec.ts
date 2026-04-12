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

test.describe('CacheHint Metadata Wire Protocol (Wave 4)', () => {

  test('@smoke conservative hint emits policy and explicit cache key', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache-hint');
    try {
      await client.connect();
      client.send('conservative');
      await client.waitForDone(15_000);

      expect(client.metadata.get('cacheHint.policy')).toBe('CONSERVATIVE');
      expect(client.metadata.get('cacheHint.enabled')).toBe(true);
      expect(client.metadata.get('cacheHint.key')).toBe('test-key');
    } finally {
      client.close();
    }
  });

  test('aggressive hint emits AGGRESSIVE policy and key', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache-hint');
    try {
      await client.connect();
      client.send('aggressive');
      await client.waitForDone(15_000);

      expect(client.metadata.get('cacheHint.policy')).toBe('AGGRESSIVE');
      expect(client.metadata.get('cacheHint.enabled')).toBe(true);
      expect(client.metadata.get('cacheHint.key')).toBe('agg-key');
    } finally {
      client.close();
    }
  });

  test('none hint emits NONE policy and disabled flag', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache-hint');
    try {
      await client.connect();
      client.send('none');
      await client.waitForDone(15_000);

      expect(client.metadata.get('cacheHint.policy')).toBe('NONE');
      expect(client.metadata.get('cacheHint.enabled')).toBe(false);
    } finally {
      client.close();
    }
  });

  test('fallback hint resolves key to sessionId when no explicit key', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache-hint');
    try {
      await client.connect();
      client.send('fallback');
      await client.waitForDone(15_000);

      expect(client.metadata.get('cacheHint.policy')).toBe('CONSERVATIVE');
      expect(client.metadata.get('cacheHint.enabled')).toBe(true);
      // Fallback key should be the sessionId (non-empty, not "none")
      const key = client.metadata.get('cacheHint.key') as string;
      expect(key).toBeTruthy();
      expect(key).not.toBe('none');
    } finally {
      client.close();
    }
  });
});
