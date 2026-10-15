import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectDurableChat, waitFor } from './helpers/durable-chat-client';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-durable-sessions']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Durable Session Identity', () => {
  test('client can connect and exchange messages', async () => {
    const { ws, messages, close } = await connectDurableChat(server.baseUrl);

    ws.send(JSON.stringify({ author: 'Identity', message: 'hello-durable' }));
    await waitFor(() => messages.some(m => m.includes('hello-durable')));

    close();
  });

  test('two independent connections have separate sessions', async () => {
    const conn1 = await connectDurableChat(server.baseUrl);
    const conn2 = await connectDurableChat(server.baseUrl);

    conn1.ws.send(JSON.stringify({ author: 'User1', message: 'from-user1' }));
    conn2.ws.send(JSON.stringify({ author: 'User2', message: 'from-user2' }));

    // Both should receive both messages (broadcast)
    await waitFor(() => conn1.messages.some(m => m.includes('from-user1')));
    await waitFor(() => conn1.messages.some(m => m.includes('from-user2')));
    await waitFor(() => conn2.messages.some(m => m.includes('from-user1')));
    await waitFor(() => conn2.messages.some(m => m.includes('from-user2')));

    conn1.close();
    conn2.close();
  });

  // NOTE: this only verifies the endpoint RECOVERS after a restart — a fresh
  // connection can send and receive again. It does NOT prove any prior session
  // state survived (it was previously mislabeled "session state survives server
  // restart", which stayed green even if durability was broken). Actual survival
  // — reconnecting with the issued token restores the same session — is asserted
  // in durable-session-restart.spec.ts via the token round-trip.
  test('endpoint accepts fresh connections after a server restart', async () => {
    test.setTimeout(120_000);

    const conn1 = await connectDurableChat(server.baseUrl);
    conn1.ws.send(JSON.stringify({ author: 'Persistent', message: 'before-restart' }));
    await waitFor(() => conn1.messages.some(m => m.includes('before-restart')));
    conn1.close();

    await server.restart();

    const conn2 = await connectDurableChat(server.baseUrl);
    conn2.ws.send(JSON.stringify({ author: 'Persistent', message: 'after-restart' }));
    await waitFor(() => conn2.messages.some(m => m.includes('after-restart')));

    conn2.close();
  });

  test('disconnect and reconnect on same server preserves broadcaster', async () => {
    const conn1 = await connectDurableChat(server.baseUrl);
    conn1.ws.send(JSON.stringify({ author: 'Returner', message: 'first-visit' }));
    await waitFor(() => conn1.messages.some(m => m.includes('first-visit')));
    conn1.close();

    await new Promise(r => setTimeout(r, 1000));

    const conn2 = await connectDurableChat(server.baseUrl);
    conn2.ws.send(JSON.stringify({ author: 'Returner', message: 'second-visit' }));
    await waitFor(() => conn2.messages.some(m => m.includes('second-visit')));

    // Verify broadcaster still works with a third client
    const conn3 = await connectDurableChat(server.baseUrl);
    conn2.ws.send(JSON.stringify({ author: 'Returner', message: 'broadcast-check' }));
    await waitFor(() => conn3.messages.some(m => m.includes('broadcast-check')), 30_000);

    conn2.close();
    conn3.close();
  });
});
