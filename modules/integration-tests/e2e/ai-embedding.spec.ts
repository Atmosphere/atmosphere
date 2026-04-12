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

test.describe('EmbeddingRuntime SPI Wire Protocol (Wave 5)', () => {

  test('@smoke single embed returns vector with text length as first element', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/embedding');
    try {
      await client.connect();
      client.send('embed:hello');
      await client.waitForDone(15_000);

      expect(client.metadata.get('embedding.dimensions')).toBe(8);
      expect(client.metadata.get('embedding.vector_0')).toBe(5.0);
    } finally {
      client.close();
    }
  });

  test('embedAll returns correct count and first-elements per input', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/embedding');
    try {
      await client.connect();
      client.send('embedAll:a,bb,ccc');
      await client.waitForDone(15_000);

      expect(client.metadata.get('embedding.count')).toBe(3);
      expect(client.metadata.get('embedding.vector_0_0')).toBe(1.0);
      expect(client.metadata.get('embedding.vector_1_0')).toBe(2.0);
      expect(client.metadata.get('embedding.vector_2_0')).toBe(3.0);
    } finally {
      client.close();
    }
  });

  test('runtime info exposes name, availability, priority, and dimensions', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/embedding');
    try {
      await client.connect();
      client.send('info');
      await client.waitForDone(15_000);

      expect(client.metadata.get('embedding.name')).toBe('fake-e2e');
      expect(client.metadata.get('embedding.available')).toBe(true);
      expect(client.metadata.get('embedding.priority')).toBe(100);
      expect(client.metadata.get('embedding.dimensions')).toBe(8);
    } finally {
      client.close();
    }
  });
});
