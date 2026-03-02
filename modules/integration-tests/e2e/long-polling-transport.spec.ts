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

  test('long-polling client receives its own echo', async () => {
    const lp = new LongPollingClient(server.baseUrl, '/atmosphere/chat');
    await lp.connect();

    // Give the poll loop time to start
    await new Promise(r => setTimeout(r, 1000));

    // Send via WebSocket — the LP client's poll should pick up the broadcast
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    sender.ws.send(JSON.stringify({ author: 'LP-Echo', message: 'echo-check' }));

    await waitFor(() => lp.messages.some(m => m.includes('echo-check')), 15_000);

    lp.close();
    sender.close();
  });
});
