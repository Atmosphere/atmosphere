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

test.describe('AgentLifecycleListener Events (Wave 3)', () => {

  test('@smoke listener captures onToolCall and onToolResult', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/lifecycle-listener');
    try {
      await client.connect();
      client.send('listen');
      await client.waitForDone(15_000);

      expect(client.metadata.get('listener.toolCall.count')).toBe(1);
      expect(client.metadata.get('listener.toolCall.name')).toBe('get_weather');
      expect(client.metadata.get('listener.toolResult.count')).toBe(1);
      expect(client.metadata.get('listener.toolResult.name')).toBe('get_weather');
      expect(client.metadata.get('listener.toolResult.preview')).toBe('{"temp":22}');
    } finally {
      client.close();
    }
  });

  test('throwing listener does not prevent recording listener from capturing', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/lifecycle-listener');
    try {
      await client.connect();
      client.send('error-listener');
      await client.waitForDone(15_000);

      // The recording listener (added first) must still capture
      // despite the throwing listener (added second) exploding
      expect(client.metadata.get('listener.toolCall.count')).toBe(1);
      expect(client.metadata.get('listener.toolCall.name')).toBe('get_weather');
      expect(client.metadata.get('listener.toolResult.count')).toBe(1);
    } finally {
      client.close();
    }
  });
});
