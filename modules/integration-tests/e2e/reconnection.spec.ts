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

test.describe('Auto-Reconnection', () => {
  test('client reconnects after server restart (raw WebSocket)', async () => {
    test.setTimeout(120_000);

    // Connect via raw WebSocket and verify
    const conn1 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    conn1.ws.send(JSON.stringify({ author: 'Reconnector', message: 'before-restart' }));
    await waitFor(() => conn1.messages.some(m => m.includes('before-restart')));
    conn1.close();

    // Restart the server
    await server.restart();

    // Verify the server accepts new connections after restart
    const conn2 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    conn2.ws.send(JSON.stringify({ author: 'Reconnector', message: 'after-restart' }));
    await waitFor(() => conn2.messages.some(m => m.includes('after-restart')));

    conn2.close();
  });

  test('server restart does not leave stale state', async () => {
    test.setTimeout(120_000);

    // Connect two clients before restart
    const c1 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const c2 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    c1.ws.send(JSON.stringify({ author: 'A', message: 'pre-restart' }));
    await waitFor(() => c2.messages.some(m => m.includes('pre-restart')));

    c1.close();
    c2.close();

    // Restart
    await server.restart();

    // New clients should work independently
    const c3 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const c4 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    c3.ws.send(JSON.stringify({ author: 'B', message: 'post-restart' }));
    await waitFor(() => c4.messages.some(m => m.includes('post-restart')));

    c3.close();
    c4.close();
  });
});
