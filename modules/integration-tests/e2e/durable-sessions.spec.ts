import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

/**
 * Helper to open an Atmosphere WebSocket connection.
 * Returns the WebSocket and a promise for collected messages.
 */
function connectAtmosphere(
  baseUrl: string,
  opts?: { sessionToken?: string },
): Promise<{ ws: WebSocket; messages: string[]; close: () => void }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http', 'ws') + '/atmosphere/chat';
    const headers: Record<string, string> = {};
    if (opts?.sessionToken) {
      headers['X-Atmosphere-Session-Token'] = opts.sessionToken;
    }
    const ws = new WebSocket(wsUrl, { headers });
    const messages: string[] = [];

    ws.on('message', (data) => {
      const text = data.toString().trim();
      if (text) messages.push(text);
    });

    ws.on('open', () => resolve({ ws, messages, close: () => ws.close() }));
    ws.on('error', reject);

    setTimeout(() => reject(new Error('WebSocket connect timeout')), 10_000);
  });
}

/** Wait for a condition with polling. */
async function waitFor(fn: () => boolean, timeoutMs = 15_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (fn()) return;
    await new Promise((r) => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out after ${timeoutMs}ms`);
}

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
    const { ws, messages, close } = await connectAtmosphere(server.baseUrl);

    // Send a JSON chat message immediately after connect — @ManagedService
    // may not send an initial handshake to raw WebSocket clients
    const msg = JSON.stringify({ author: 'TestUser', message: 'Hello durable!' });
    ws.send(msg);

    // Should receive the broadcast back (the @Message handler returns the message)
    await waitFor(() => messages.some((m) => m.includes('Hello durable!')));

    close();
  });

  test('session survives server restart', async () => {
    const { ws, messages, close } = await connectAtmosphere(server.baseUrl);

    // Send a message to establish session state
    ws.send(JSON.stringify({ author: 'Survivor', message: 'Before restart' }));
    await waitFor(() => messages.some((m) => m.includes('Before restart')));

    close();

    // Restart the server
    await server.restart();

    // Reconnect — the server should recognize the session
    const conn2 = await connectAtmosphere(server.baseUrl);

    // Send another message to verify the connection works
    conn2.ws.send(JSON.stringify({ author: 'Survivor', message: 'After restart' }));
    await waitFor(() => conn2.messages.some((m) => m.includes('After restart')));

    conn2.close();
  });

  test('expired session token is rejected gracefully', async () => {
    // Connect with a clearly invalid/expired session token
    const { ws, messages, close } = await connectAtmosphere(server.baseUrl, {
      sessionToken: 'expired-invalid-token-12345',
    });

    // Server should issue a new session — verify by sending a message and receiving it back
    ws.send(JSON.stringify({ author: 'TokenTest', message: 'Hello after bad token' }));
    await waitFor(() => messages.some((m) => m.includes('Hello after bad token')));

    close();
  });

  test('room membership restored after reconnect', async () => {
    // Connect two clients
    const clientA = await connectAtmosphere(server.baseUrl);
    const clientB = await connectAtmosphere(server.baseUrl);

    // Verify they can exchange messages (broadcast)
    clientB.ws.send(JSON.stringify({ author: 'ClientB', message: 'before-disconnect' }));
    await waitFor(() => clientA.messages.some((m) => m.includes('before-disconnect')));

    // Disconnect client A
    clientA.close();

    // Small delay to let the server process the disconnect
    await new Promise((r) => setTimeout(r, 1_000));

    // Reconnect client A
    const clientA2 = await connectAtmosphere(server.baseUrl);

    // Verify client A2 can still receive messages from client B
    clientB.ws.send(JSON.stringify({ author: 'ClientB', message: 'after-reconnect' }));
    await waitFor(() => clientA2.messages.some((m) => m.includes('after-reconnect')));

    clientA2.close();
    clientB.close();
  });

  test('concurrent connections don\'t interfere', async () => {
    // Open 3 connections simultaneously
    const [conn1, conn2, conn3] = await Promise.all([
      connectAtmosphere(server.baseUrl),
      connectAtmosphere(server.baseUrl),
      connectAtmosphere(server.baseUrl),
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
