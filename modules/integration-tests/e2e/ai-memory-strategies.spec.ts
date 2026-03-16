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

test.describe('Token Window Memory Strategy E2E', () => {

  test('first prompt has no history', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/memory-token-window');
    try {
      await client.connect();
      client.send('Hello');
      await client.waitForDone(15_000);

      expect(client.metadata.get('strategy')).toBe('token-window');
      expect(client.metadata.get('rawHistoryCount')).toBe(0);
    } finally {
      client.close();
    }
  });

  test('second prompt includes first turn in history', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/memory-token-window');
    try {
      await client.connect();

      client.send('Short message');
      await client.waitForDone(15_000);
      expect(client.metadata.get('rawHistoryCount')).toBe(0);

      client.reset();

      client.send('Second message');
      await client.waitForDone(15_000);
      expect(client.metadata.get('strategy')).toBe('token-window');
      expect(client.metadata.get('rawHistoryCount')).toBe(2);
      // Selected should equal raw since we're within the token budget
      expect(client.metadata.get('selectedHistoryCount')).toBe(2);
    } finally {
      client.close();
    }
  });

  test('long messages exceed token budget and get trimmed', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/memory-token-window');
    try {
      await client.connect();

      // Send several long messages to exceed the 200-token budget
      const longMsg = 'A'.repeat(300); // ~75 tokens each at chars/4
      for (let i = 0; i < 5; i++) {
        client.send(`${longMsg} message ${i}`);
        await client.waitForDone(15_000);
        client.reset();
      }

      // Final prompt should have trimmed history
      client.send('Final');
      await client.waitForDone(15_000);

      const raw = client.metadata.get('rawHistoryCount') as number;
      const selected = client.metadata.get('selectedHistoryCount') as number;
      const totalChars = client.metadata.get('totalHistoryChars') as number;

      expect(raw).toBe(10); // 5 turns × 2 messages each
      expect(selected).toBeLessThan(raw); // Token window should trim
      // Total chars in selected should be roughly within 200*4 = 800 chars
      expect(totalChars).toBeLessThan(1000);
    } finally {
      client.close();
    }
  });
});

test.describe('Summarizing Memory Strategy E2E', () => {

  test('first prompt has no history and uses summarizing strategy', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/memory-summarizing');
    try {
      await client.connect();
      client.send('Hello');
      await client.waitForDone(15_000);

      expect(client.metadata.get('strategy')).toBe('summarizing');
      expect(client.metadata.get('rawHistoryCount')).toBe(0);
      expect(client.metadata.get('hasSummary')).toBe(false);
    } finally {
      client.close();
    }
  });

  test('many turns produce a summary of old messages', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/memory-summarizing');
    try {
      await client.connect();

      // Send enough messages to trigger summarization
      // The SummarizingStrategy has recentWindowSize=4,
      // so after 4+ non-system messages, old ones get summarized
      for (let i = 0; i < 6; i++) {
        client.send(`Turn ${i}`);
        await client.waitForDone(15_000);
        client.reset();
      }

      // Final prompt should have a summary of old messages
      client.send('Final check');
      await client.waitForDone(15_000);

      expect(client.metadata.get('strategy')).toBe('summarizing');
      expect(client.metadata.get('hasSummary')).toBe(true);

      // Selected count should include: system summary + recent window
      const selected = client.metadata.get('selectedHistoryCount') as number;
      const raw = client.metadata.get('rawHistoryCount') as number;
      expect(raw).toBe(12); // 6 turns × 2 messages each
      // Should be fewer than raw because old messages are summarized
      expect(selected).toBeLessThan(raw);
    } finally {
      client.close();
    }
  });
});
