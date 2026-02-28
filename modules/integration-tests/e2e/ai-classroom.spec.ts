import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8098;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Classroom E2E', () => {

  test('multi-client broadcast: all clients receive streamed tokens', async () => {
    const clients = [
      new AiWsClient(server.wsUrl, '/ai/classroom/math'),
      new AiWsClient(server.wsUrl, '/ai/classroom/math'),
      new AiWsClient(server.wsUrl, '/ai/classroom/math'),
    ];

    try {
      for (const c of clients) await c.connect();

      // Only client 0 sends the prompt
      clients[0].send('What is 2+2?');

      // ALL 3 clients should receive the streamed tokens (broadcast)
      for (const c of clients) {
        await c.waitForDone(15_000);
        expect(c.tokens.length).toBeGreaterThan(0);
        expect(c.fullResponse).toContain('[MATH]');
      }

      // All 3 clients received IDENTICAL content
      expect(clients[0].fullResponse).toBe(clients[1].fullResponse);
      expect(clients[1].fullResponse).toBe(clients[2].fullResponse);
    } finally {
      clients.forEach(c => c.close());
    }
  });

  test('room isolation: math and code rooms are independent', async () => {
    const mathClient = new AiWsClient(server.wsUrl, '/ai/classroom/math');
    const codeClient = new AiWsClient(server.wsUrl, '/ai/classroom/code');

    try {
      await mathClient.connect();
      await codeClient.connect();

      // Math client sends a question
      mathClient.send('Explain algebra');

      // Math client should receive tokens
      await mathClient.waitForDone(15_000);
      expect(mathClient.tokens.length).toBeGreaterThan(0);
      expect(mathClient.fullResponse).toContain('[MATH]');

      // Code client should NOT have received any token/complete events
      await new Promise(r => setTimeout(r, 500));
      expect(codeClient.events.filter(e => e.type === 'token').length).toBe(0);
      expect(codeClient.events.filter(e => e.type === 'complete').length).toBe(0);
    } finally {
      mathClient.close();
      codeClient.close();
    }
  });

  test('room persona: metadata reflects the room', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/classroom/math');
    try {
      await client.connect();
      client.send('Question for math room');
      await client.waitForDone(15_000);

      expect(client.metadata.get('room')).toBe('math');
      expect(client.fullResponse).toContain('[MATH]');
    } finally {
      client.close();
    }
  });

  test('code room gets code persona', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/classroom/code');
    try {
      await client.connect();
      client.send('How do I write a function?');
      await client.waitForDone(15_000);

      expect(client.metadata.get('room')).toBe('code');
      expect(client.fullResponse).toContain('[CODE]');
    } finally {
      client.close();
    }
  });

  test('sequential prompts: all room members see both responses', async () => {
    const client1 = new AiWsClient(server.wsUrl, '/ai/classroom/math');
    const client2 = new AiWsClient(server.wsUrl, '/ai/classroom/math');

    try {
      await client1.connect();
      await client2.connect();

      // First prompt: client 1 asks
      client1.send('First question');
      await client1.waitForDone(15_000);
      await client2.waitForDone(15_000);

      const firstResponse1 = client1.fullResponse;
      const firstResponse2 = client2.fullResponse;
      expect(firstResponse1.length).toBeGreaterThan(0);
      expect(firstResponse1).toBe(firstResponse2);

      // Reset and send second prompt: client 2 asks
      client1.reset();
      client2.reset();

      client2.send('Second question');
      await client1.waitForDone(15_000);
      await client2.waitForDone(15_000);

      expect(client1.fullResponse.length).toBeGreaterThan(0);
      expect(client1.fullResponse).toBe(client2.fullResponse);
    } finally {
      client1.close();
      client2.close();
    }
  });

  test('late joiner sends own question and receives response', async () => {
    const earlyClient = new AiWsClient(server.wsUrl, '/ai/classroom/math');
    try {
      await earlyClient.connect();
      earlyClient.send('Early question');
      await earlyClient.waitForDone(15_000);
    } finally {
      earlyClient.close();
    }

    // Late joiner connects after first stream completes
    const lateClient = new AiWsClient(server.wsUrl, '/ai/classroom/math');
    try {
      await lateClient.connect();
      lateClient.send('Late question');
      await lateClient.waitForDone(15_000);

      expect(lateClient.tokens.length).toBeGreaterThan(0);
      expect(lateClient.fullResponse).toContain('[MATH]');
    } finally {
      lateClient.close();
    }
  });
});
