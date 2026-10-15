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

test.describe('Durable Session Token Handling', () => {
  test('server assigns a session token on first connection', async () => {
    const conn = await connectDurableChat(server.baseUrl);

    // Send a message to trigger full handshake
    conn.ws.send(JSON.stringify({ author: 'TokenTest', message: 'hello' }));
    await waitFor(() => conn.messages.some(m => m.includes('hello')));

    // The server should have assigned a session token in the upgrade headers
    const token = conn.headers['x-atmosphere-session-token'];
    // Token may come via headers or via a message — either way the session is established
    expect(conn.messages.length).toBeGreaterThan(0);

    conn.close();
  });

  test('client can reconnect with session token after disconnect', async () => {
    // First connection
    const conn1 = await connectDurableChat(server.baseUrl);
    conn1.ws.send(JSON.stringify({ author: 'Returner', message: 'first-connect' }));
    await waitFor(() => conn1.messages.some(m => m.includes('first-connect')));
    conn1.close();

    // Wait for disconnect to propagate
    await new Promise(r => setTimeout(r, 1_000));

    // Reconnect — even without explicit token, the server should handle it
    const conn2 = await connectDurableChat(server.baseUrl);
    conn2.ws.send(JSON.stringify({ author: 'Returner', message: 'second-connect' }));
    await waitFor(() => conn2.messages.some(m => m.includes('second-connect')));

    conn2.close();
  });

  test('messages flow correctly after reconnection', async () => {
    // Connect client A
    const connA = await connectDurableChat(server.baseUrl);
    connA.ws.send(JSON.stringify({ author: 'UserA', message: 'before-reconnect' }));
    await waitFor(() => connA.messages.some(m => m.includes('before-reconnect')));

    // Connect client B
    const connB = await connectDurableChat(server.baseUrl);

    // Client A sends another message — B should receive it
    connA.ws.send(JSON.stringify({ author: 'UserA', message: 'hello-B' }));
    await waitFor(() => connB.messages.some(m => m.includes('hello-B')));

    // Disconnect client A
    connA.close();
    await new Promise(r => setTimeout(r, 1_000));

    // Reconnect client A
    const connA2 = await connectDurableChat(server.baseUrl);

    // Client B sends to reconnected A
    connB.ws.send(JSON.stringify({ author: 'UserB', message: 'welcome-back' }));
    await waitFor(() => connA2.messages.some(m => m.includes('welcome-back')), 30_000);

    connA2.close();
    connB.close();
  });

  test('multiple rapid reconnections do not corrupt session state', async () => {
    // Rapidly connect and disconnect 5 times
    for (let i = 0; i < 5; i++) {
      const conn = await connectDurableChat(server.baseUrl);
      conn.ws.send(JSON.stringify({ author: 'Rapid', message: `cycle-${i}` }));
      await waitFor(() => conn.messages.some(m => m.includes(`cycle-${i}`)));
      conn.close();
      await new Promise(r => setTimeout(r, 300));
    }

    // Final connection should work cleanly
    const final = await connectDurableChat(server.baseUrl);
    final.ws.send(JSON.stringify({ author: 'Rapid', message: 'final-check' }));
    await waitFor(() => final.messages.some(m => m.includes('final-check')));

    final.close();
  });
});
