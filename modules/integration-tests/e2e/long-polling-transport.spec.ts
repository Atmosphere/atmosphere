import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { LongPollingClient, connectWebSocket, waitFor } from './helpers/transport-helper';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Long-Polling Transport', () => {
  test('client can connect via long-polling', async () => {
    const lp = new LongPollingClient(server.baseUrl, '/atmosphere/chat');
    await lp.connect();

    // Connection established — no error thrown
    lp.close();
  });

  test('client receives broadcast messages via long-polling', async () => {
    const lp = new LongPollingClient(server.baseUrl, '/atmosphere/chat');
    await lp.connect();

    // Send a message via WebSocket to broadcast
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 1000));

    sender.ws.send(JSON.stringify({ author: 'LP-Test', message: 'Hello via LP!' }));

    // Long-polling client should receive the broadcast
    await waitFor(() => lp.messages.some(m => m.includes('Hello via LP!')), 15_000);

    lp.close();
    sender.close();
  });

  test('client can send messages via long-polling POST', async () => {
    const lp = new LongPollingClient(server.baseUrl, '/atmosphere/chat');
    await lp.connect();

    // Also connect a WebSocket listener to verify broadcast
    const listener = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 1000));

    // Send via long-polling POST
    await lp.send(JSON.stringify({ author: 'LP-Sender', message: 'Sent via LP POST!' }));

    // WebSocket listener should receive the broadcast
    await waitFor(
      () => listener.messages.some(m => m.includes('Sent via LP POST!')),
      15_000,
    );

    lp.close();
    listener.close();
  });
});
