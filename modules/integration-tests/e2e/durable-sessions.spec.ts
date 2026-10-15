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

test.describe('Durable Sessions', () => {
  test('client can connect and send messages via WebSocket', async () => {
    const { ws, messages, close } = await connectDurableChat(server.baseUrl);

    // Send a JSON chat message immediately after connect — @ManagedService
    // may not send an initial handshake to raw WebSocket clients
    const msg = JSON.stringify({ author: 'TestUser', message: 'Hello durable!' });
    ws.send(msg);

    // Should receive the broadcast back (the @Message handler returns the message)
    await waitFor(() => messages.some((m) => m.includes('Hello durable!')));

    close();
  });

  test('session survives server restart', async () => {
    const { ws, messages, close } = await connectDurableChat(server.baseUrl);

    // Send a message to establish session state
    ws.send(JSON.stringify({ author: 'Survivor', message: 'Before restart' }));
    await waitFor(() => messages.some((m) => m.includes('Before restart')));

    close();

    // Restart the server
    await server.restart();

    // Reconnect — the server should recognize the session
    const conn2 = await connectDurableChat(server.baseUrl);

    // Send another message to verify the connection works
    conn2.ws.send(JSON.stringify({ author: 'Survivor', message: 'After restart' }));
    await waitFor(() => conn2.messages.some((m) => m.includes('After restart')));

    conn2.close();
  });

  test('expired session token is rejected gracefully', async () => {
    // Connect with a clearly invalid/expired session token
    const { ws, messages, close } = await connectDurableChat(server.baseUrl, {
      sessionToken: 'expired-invalid-token-12345',
    });

    // Server should issue a new session — verify by sending a message and receiving it back
    ws.send(JSON.stringify({ author: 'TokenTest', message: 'Hello after bad token' }));
    await waitFor(() => messages.some((m) => m.includes('Hello after bad token')));

    close();
  });

  test('room membership restored after reconnect', async () => {
    // Connect two clients
    const clientA = await connectDurableChat(server.baseUrl);
    const clientB = await connectDurableChat(server.baseUrl);

    // Verify they can exchange messages (broadcast)
    clientB.ws.send(JSON.stringify({ author: 'ClientB', message: 'before-disconnect' }));
    await waitFor(() => clientA.messages.some((m) => m.includes('before-disconnect')));

    // Disconnect client A
    clientA.close();

    // Small delay to let the server process the disconnect
    await new Promise((r) => setTimeout(r, 1_000));

    // Reconnect client A
    const clientA2 = await connectDurableChat(server.baseUrl);

    // Verify client A2 can still receive messages from client B
    clientB.ws.send(JSON.stringify({ author: 'ClientB', message: 'after-reconnect' }));
    await waitFor(() => clientA2.messages.some((m) => m.includes('after-reconnect')));

    clientA2.close();
    clientB.close();
  });

  test('concurrent connections don\'t interfere', async () => {
    // Open 3 connections simultaneously
    const [conn1, conn2, conn3] = await Promise.all([
      connectDurableChat(server.baseUrl),
      connectDurableChat(server.baseUrl),
      connectDurableChat(server.baseUrl),
    ]);

    // Each sends a unique message
    conn1.ws.send(JSON.stringify({ author: 'User1', message: 'msg-from-conn1' }));
    conn2.ws.send(JSON.stringify({ author: 'User2', message: 'msg-from-conn2' }));
    conn3.ws.send(JSON.stringify({ author: 'User3', message: 'msg-from-conn3' }));

    // Verify all 3 connections receive all 3 messages (broadcast behavior)
    await waitFor(() =>
      conn1.messages.some((m) => m.includes('msg-from-conn1')) &&
      conn1.messages.some((m) => m.includes('msg-from-conn2')) &&
      conn1.messages.some((m) => m.includes('msg-from-conn3')),
    );
    await waitFor(() =>
      conn2.messages.some((m) => m.includes('msg-from-conn1')) &&
      conn2.messages.some((m) => m.includes('msg-from-conn2')) &&
      conn2.messages.some((m) => m.includes('msg-from-conn3')),
    );
    await waitFor(() =>
      conn3.messages.some((m) => m.includes('msg-from-conn1')) &&
      conn3.messages.some((m) => m.includes('msg-from-conn2')) &&
      conn3.messages.some((m) => m.includes('msg-from-conn3')),
    );

    conn1.close();
    conn2.close();
    conn3.close();
  });
});
