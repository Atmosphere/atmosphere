import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Message Ordering', () => {
  test('all clients receive all messages from concurrent senders', async () => {
    // Connect 3 clients
    const clients = await Promise.all([
      connectWebSocket(server.baseUrl, '/atmosphere/chat'),
      connectWebSocket(server.baseUrl, '/atmosphere/chat'),
      connectWebSocket(server.baseUrl, '/atmosphere/chat'),
    ]);
    await new Promise(r => setTimeout(r, 1000));

    // Each client sends a uniquely identifiable message simultaneously
    await Promise.all(
      clients.map((client, i) =>
        new Promise<void>((resolve) => {
          client.ws.send(JSON.stringify({
            author: `Client${i}`,
            message: `msg-from-${i}`,
          }));
          resolve();
        }),
      ),
    );

    // All clients should receive all 3 messages
    for (const client of clients) {
      await waitFor(
        () =>
          client.messages.filter(m => m.includes('msg-from-')).length >= 3,
        15_000,
      );
    }

    // Verify each message was received
    for (const client of clients) {
      const allMsgs = client.messages.join(' ');
      expect(allMsgs).toContain('msg-from-0');
      expect(allMsgs).toContain('msg-from-1');
      expect(allMsgs).toContain('msg-from-2');
    }

    clients.forEach(c => c.close());
  });

  test('message order is consistent across all receivers', async () => {
    // Connect a sender and 3 listeners
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const listeners = await Promise.all([
      connectWebSocket(server.baseUrl, '/atmosphere/chat'),
      connectWebSocket(server.baseUrl, '/atmosphere/chat'),
      connectWebSocket(server.baseUrl, '/atmosphere/chat'),
    ]);
    await new Promise(r => setTimeout(r, 1000));

    // Send 10 sequentially numbered messages from one source
    for (let i = 0; i < 10; i++) {
      sender.ws.send(JSON.stringify({
        author: 'Sender',
        message: `seq-${i}`,
      }));
      // Small delay to ensure ordering at the sender side
      await new Promise(r => setTimeout(r, 50));
    }

    // Wait for all listeners to receive all 10 messages
    for (const listener of listeners) {
      await waitFor(
        () => listener.messages.filter(m => m.includes('seq-')).length >= 10,
        15_000,
      );
    }

    // Extract the sequence of seq-N from each listener
    function extractSequence(messages: string[]): number[] {
      return messages
        .filter(m => m.includes('seq-'))
        .map(m => {
          const match = m.match(/seq-(\d+)/);
          return match ? parseInt(match[1], 10) : -1;
        })
        .filter(n => n >= 0);
    }

    const sequences = listeners.map(l => extractSequence(l.messages));

    // All listeners should have the same ordering
    for (let i = 1; i < sequences.length; i++) {
      expect(sequences[i]).toEqual(sequences[0]);
    }

    // Messages should be in ascending order (0, 1, 2, ..., 9)
    for (const seq of sequences) {
      for (let i = 1; i < seq.length; i++) {
        expect(seq[i]).toBeGreaterThan(seq[i - 1]);
      }
    }

    sender.close();
    listeners.forEach(l => l.close());
  });
});
