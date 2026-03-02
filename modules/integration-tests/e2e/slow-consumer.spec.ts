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

test.describe('Slow Consumer', () => {
  test('fast client receives all messages even when slow client is connected', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const fastClient = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const slowClient = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 1000));

    const messageCount = 20;

    // Send messages rapidly
    for (let i = 0; i < messageCount; i++) {
      sender.ws.send(JSON.stringify({
        author: 'Rapid',
        message: `rapid-${i}`,
      }));
    }

    // Fast client should receive all messages promptly
    await waitFor(
      () => fastClient.messages.filter(m => m.includes('rapid-')).length >= messageCount,
      15_000,
    );

    // Slow client should also eventually receive all messages
    await waitFor(
      () => slowClient.messages.filter(m => m.includes('rapid-')).length >= messageCount,
      30_000,
    );

    sender.close();
    fastClient.close();
    slowClient.close();
  });

  test('broadcaster handles burst of messages to multiple consumers', async () => {
    // Connect 5 clients
    const clients = await Promise.all(
      Array.from({ length: 5 }, () =>
        connectWebSocket(server.baseUrl, '/atmosphere/chat'),
      ),
    );
    await new Promise(r => setTimeout(r, 1000));

    const sender = clients[0];
    const receivers = clients.slice(1);

    // Burst-send 50 messages
    for (let i = 0; i < 50; i++) {
      sender.ws.send(JSON.stringify({
        author: 'Burst',
        message: `burst-${i}`,
      }));
    }

    // All receivers should get all 50
    for (const receiver of receivers) {
      await waitFor(
        () => receiver.messages.filter(m => m.includes('burst-')).length >= 50,
        30_000,
      );
    }

    clients.forEach(c => c.close());
  });
});
