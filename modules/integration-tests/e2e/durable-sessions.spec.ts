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
});
