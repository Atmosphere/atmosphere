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

test.describe('models() Runtime Truth (Wave 1)', () => {

  test('@smoke models() returns configured model names', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/models');
    try {
      await client.connect();
      client.send('models');
      await client.waitForDone(15_000);

      expect(client.metadata.get('model.count')).toBe(3);
      expect(client.metadata.get('model.0')).toBe('gpt-4o');
      expect(client.metadata.get('model.1')).toBe('gemini-2.5-flash');
      expect(client.metadata.get('model.2')).toBe('test-model');
    } finally {
      client.close();
    }
  });

  test('capabilities include TEXT_STREAMING and TOOL_APPROVAL', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/models');
    try {
      await client.connect();
      client.send('capabilities');
      await client.waitForDone(15_000);

      const count = client.metadata.get('capability.count') as number;
      expect(count).toBeGreaterThanOrEqual(2);

      // Collect all capability values
      const caps: string[] = [];
      for (let i = 0; i < count; i++) {
        const cap = client.metadata.get(`capability.${i}`) as string;
        if (cap) caps.push(cap);
      }

      expect(caps).toContain('TEXT_STREAMING');
      expect(caps).toContain('TOOL_APPROVAL');
    } finally {
      client.close();
    }
  });
});
