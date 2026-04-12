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

test.describe('RetryPolicy Per-Request Override Wire Protocol (Wave 6)', () => {

  test('@smoke default policy echoes standard retry fields', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/retry-policy');
    try {
      await client.connect();
      client.send('default');
      await client.waitForDone(15_000);

      expect(client.metadata.get('retry.maxRetries')).toBe(3);
      expect(client.metadata.get('retry.backoffMultiplier')).toBe(2.0);
      const delay = client.metadata.get('retry.initialDelay') as number;
      expect(delay).toBeGreaterThan(0);
    } finally {
      client.close();
    }
  });

  test('custom policy echoes overridden fields', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/retry-policy');
    try {
      await client.connect();
      client.send('custom');
      await client.waitForDone(15_000);

      expect(client.metadata.get('retry.maxRetries')).toBe(5);
      expect(client.metadata.get('retry.initialDelay')).toBe(2000);
    } finally {
      client.close();
    }
  });

  test('NONE policy echoes zero retries', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/retry-policy');
    try {
      await client.connect();
      client.send('none');
      await client.waitForDone(15_000);

      expect(client.metadata.get('retry.maxRetries')).toBe(0);
    } finally {
      client.close();
    }
  });
});
