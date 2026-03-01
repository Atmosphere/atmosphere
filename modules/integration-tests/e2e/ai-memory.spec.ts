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

test.describe('AI Conversation Memory E2E', () => {

  test('first prompt has no history', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/memory');
    try {
      await client.connect();
      client.send('Hello');
      await client.waitForDone(15_000);

      expect(client.fullResponse).toBe('HISTORY:0|Hello');
      expect(client.metadata.get('historyCount')).toBe(0);
    } finally {
      client.close();
    }
  });

  test('second prompt includes first turn in history', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/memory');
    try {
      await client.connect();

      // First prompt — no history
      client.send('First question');
      await client.waitForDone(15_000);
      expect(client.metadata.get('historyCount')).toBe(0);
      expect(client.fullResponse).toBe('HISTORY:0|First question');

      // Reset client state for next prompt
      client.reset();

      // Second prompt — should have 2 history messages (user + assistant from turn 1)
      client.send('Second question');
      await client.waitForDone(15_000);
      expect(client.metadata.get('historyCount')).toBe(2);
      expect(client.fullResponse).toBe('HISTORY:2|Second question');

      // Verify the actual history content
      expect(client.metadata.get('history_0_role')).toBe('user');
      expect(client.metadata.get('history_0_content')).toBe('First question');
      expect(client.metadata.get('history_1_role')).toBe('assistant');
      expect(client.metadata.get('history_1_content')).toBe('HISTORY:0|First question');
    } finally {
      client.close();
    }
  });

  test('three prompts accumulate history correctly', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/memory');
    try {
      await client.connect();

      // Turn 1
      client.send('Turn one');
      await client.waitForDone(15_000);
      expect(client.metadata.get('historyCount')).toBe(0);
      client.reset();

      // Turn 2
      client.send('Turn two');
      await client.waitForDone(15_000);
      expect(client.metadata.get('historyCount')).toBe(2);
      client.reset();

      // Turn 3
      client.send('Turn three');
      await client.waitForDone(15_000);
      expect(client.metadata.get('historyCount')).toBe(4);

      // Verify all 4 history messages from turns 1 and 2
      expect(client.metadata.get('history_0_role')).toBe('user');
      expect(client.metadata.get('history_0_content')).toBe('Turn one');
      expect(client.metadata.get('history_1_role')).toBe('assistant');
      expect(client.metadata.get('history_2_role')).toBe('user');
      expect(client.metadata.get('history_2_content')).toBe('Turn two');
      expect(client.metadata.get('history_3_role')).toBe('assistant');
    } finally {
      client.close();
    }
  });

  test('3 browsers maintain independent conversation history', async () => {
    const clients = [
      new AiWsClient(server.wsUrl, '/ai/memory'),
      new AiWsClient(server.wsUrl, '/ai/memory'),
      new AiWsClient(server.wsUrl, '/ai/memory'),
    ];

    try {
      // Connect all 3 clients
      for (const c of clients) await c.connect();

      // --- Turn 1: Each client sends a unique first prompt ---
      clients[0].send('Alice says hello');
      clients[1].send('Bob says hello');
      clients[2].send('Charlie says hello');

      for (const c of clients) {
        await c.waitForDone(15_000);
        // All should have 0 history on first prompt
        expect(c.metadata.get('historyCount')).toBe(0);
      }

      // Verify each client got its OWN response (not broadcast to others)
      expect(clients[0].fullResponse).toBe('HISTORY:0|Alice says hello');
      expect(clients[1].fullResponse).toBe('HISTORY:0|Bob says hello');
      expect(clients[2].fullResponse).toBe('HISTORY:0|Charlie says hello');

      // Reset all clients
      for (const c of clients) c.reset();

      // --- Turn 2: Each client sends a second prompt ---
      clients[0].send('Alice followup');
      clients[1].send('Bob followup');
      clients[2].send('Charlie followup');

      for (const c of clients) {
        await c.waitForDone(15_000);
        // All should have 2 history messages (their own first turn)
        expect(c.metadata.get('historyCount')).toBe(2);
      }

      // Client 0's history should be Alice's conversation
      expect(clients[0].metadata.get('history_0_content')).toBe('Alice says hello');
      expect(clients[0].fullResponse).toBe('HISTORY:2|Alice followup');

      // Client 1's history should be Bob's conversation
      expect(clients[1].metadata.get('history_0_content')).toBe('Bob says hello');
      expect(clients[1].fullResponse).toBe('HISTORY:2|Bob followup');

      // Client 2's history should be Charlie's conversation
      expect(clients[2].metadata.get('history_0_content')).toBe('Charlie says hello');
      expect(clients[2].fullResponse).toBe('HISTORY:2|Charlie followup');

      // Reset and do one more turn to verify 3-turn accumulation
      for (const c of clients) c.reset();

      // --- Turn 3: Only client 0 sends a third prompt ---
      clients[0].send('Alice third message');
      await clients[0].waitForDone(15_000);

      // Client 0 should have 4 history messages (2 turns)
      expect(clients[0].metadata.get('historyCount')).toBe(4);
      expect(clients[0].fullResponse).toBe('HISTORY:4|Alice third message');
    } finally {
      clients.forEach(c => c.close());
    }
  });

  test('disconnect clears memory', async () => {
    // First session: build up some history
    const client1 = new AiWsClient(server.wsUrl, '/ai/memory');
    try {
      await client1.connect();
      client1.send('Remember this');
      await client1.waitForDone(15_000);
      expect(client1.metadata.get('historyCount')).toBe(0);
    } finally {
      client1.close();
    }

    // Wait for disconnect to propagate
    await new Promise(r => setTimeout(r, 500));

    // Second session from same or different client — memory should be cleared
    const client2 = new AiWsClient(server.wsUrl, '/ai/memory');
    try {
      await client2.connect();
      client2.send('Do you remember?');
      await client2.waitForDone(15_000);

      // New connection = new resource UUID = fresh memory
      expect(client2.metadata.get('historyCount')).toBe(0);
      expect(client2.fullResponse).toBe('HISTORY:0|Do you remember?');
    } finally {
      client2.close();
    }
  });
});
