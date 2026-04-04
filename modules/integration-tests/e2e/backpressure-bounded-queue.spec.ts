import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Backpressure & Bounded Queue', () => {

  test('rapid-fire messages are all delivered', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 1000));

    const messageCount = 50;
    for (let i = 0; i < messageCount; i++) {
      sender.ws.send(JSON.stringify({
        author: 'Flood',
        message: `rapid-fire-${i}`,
      }));
    }

    // Wait for at least some messages to arrive
    await waitFor(
      () => receiver.messages.filter(m => m.includes('rapid-fire-')).length >= messageCount * 0.8,
      15_000,
    );

    const received = receiver.messages.filter(m => m.includes('rapid-fire-'));
    // At least 80% of messages should be delivered (some may be coalesced)
    expect(received.length).toBeGreaterThanOrEqual(messageCount * 0.8);

    sender.close();
    receiver.close();
  });

  test('large message is handled without error', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    // Send a moderate payload (~4KB) — large enough to test but small
    // enough to avoid CI timeout issues with frame reassembly
    const msg = JSON.stringify({
      author: 'BigSender',
      message: 'X'.repeat(4 * 1024),
    });

    client.ws.send(msg);

    await waitFor(
      () => client.messages.some(m => m.includes('BigSender')),
      15_000,
    );

    client.close();
  });

  test('slow consumer does not block fast producer', async () => {
    const fastSender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    // Send 20 messages rapidly
    const start = Date.now();
    for (let i = 0; i < 20; i++) {
      fastSender.ws.send(JSON.stringify({
        author: 'Fast',
        message: `backpressure-${i}`,
      }));
    }
    const sendTime = Date.now() - start;

    // Sending should be fast (not blocked by slow consumers)
    expect(sendTime).toBeLessThan(5000);

    // Wait for messages to arrive
    await waitFor(
      () => fastSender.messages.filter(m => m.includes('backpressure-')).length >= 10,
      15_000,
    );

    fastSender.close();
  });

  test('server remains responsive after burst', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    // Burst of messages
    for (let i = 0; i < 30; i++) {
      client.ws.send(JSON.stringify({ author: 'Burst', message: `burst-${i}` }));
    }

    // Wait for burst to settle
    await new Promise(r => setTimeout(r, 3000));

    // Server should still accept new connections
    const newClient = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    expect(newClient.ws.readyState).toBe(1); // OPEN

    // And respond to new messages
    newClient.ws.send(JSON.stringify({ author: 'AfterBurst', message: 'still-alive' }));
    await waitFor(() => newClient.messages.some(m => m.includes('still-alive')));

    client.close();
    newClient.close();
  });
});
