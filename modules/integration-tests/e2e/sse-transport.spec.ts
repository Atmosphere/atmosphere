import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectSSE, connectWebSocket, waitFor } from './helpers/transport-helper';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('SSE Transport', () => {
  test('client can connect via SSE', async () => {
    const { es, close } = await connectSSE(server.baseUrl, '/atmosphere/chat');
    // readyState 1 = OPEN
    expect(es.readyState).toBe(1);
    close();
  });

  test('client receives broadcast messages via SSE', async () => {
    const { messages: messages1, close: close1 } =
      await connectSSE(server.baseUrl, '/atmosphere/chat');

    const { messages: messages2, close: close2 } =
      await connectSSE(server.baseUrl, '/atmosphere/chat');

    // Wait for both connections to settle
    await new Promise(r => setTimeout(r, 1000));

    // SSE is read-only — send via a WebSocket sender
    
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');

    const msg = JSON.stringify({ author: 'SSE-Test', message: 'Hello via SSE!' });
    sender.ws.send(msg);

    // Both SSE clients should receive the broadcast
    await waitFor(() => messages1.some(m => m.includes('Hello via SSE!')), 10_000);
    await waitFor(() => messages2.some(m => m.includes('Hello via SSE!')), 10_000);

    close1();
    close2();
    sender.close();
  });

  test('SSE connection receives multiple sequential messages', async () => {
    const { messages, close } = await connectSSE(server.baseUrl, '/atmosphere/chat');

    
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    // Send 3 messages
    for (let i = 1; i <= 3; i++) {
      sender.ws.send(JSON.stringify({ author: 'Sender', message: `Message ${i}` }));
      await new Promise(r => setTimeout(r, 200));
    }

    await waitFor(() =>
      messages.filter(m => m.includes('Message')).length >= 3,
      10_000,
    );

    close();
    sender.close();
  });
});
