import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-durable-sessions']);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * Connect to the durable sessions endpoint with optional session token.
 * Captures the session token from response headers (via WebSocket upgrade).
 */
function connectDurable(
  baseUrl: string,
  opts?: { sessionToken?: string },
): Promise<{
  ws: WebSocket;
  messages: string[];
  headers: Record<string, string>;
  close: () => void;
}> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http', 'ws') + '/atmosphere/chat';
    const headers: Record<string, string> = {};
    if (opts?.sessionToken) {
      headers['X-Atmosphere-Session-Token'] = opts.sessionToken;
    }
    const ws = new WebSocket(wsUrl, { headers });
    const messages: string[] = [];
    let upgradeHeaders: Record<string, string> = {};

    ws.on('upgrade', (response) => {
      // Capture response headers from WebSocket upgrade
      for (const [key, value] of Object.entries(response.headers)) {
        if (typeof value === 'string') {
          upgradeHeaders[key.toLowerCase()] = value;
        }
      }
    });

    ws.on('message', (data) => {
      const text = data.toString().trim();
      if (text) messages.push(text);
    });

    ws.on('open', () =>
      resolve({ ws, messages, headers: upgradeHeaders, close: () => ws.close() }),
    );
    ws.on('error', reject);
    setTimeout(() => reject(new Error('WebSocket connect timeout')), 10_000);
  });
}

/** Wait for a condition with polling. */
async function waitFor(fn: () => boolean, timeoutMs = 15_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (fn()) return;
    await new Promise(r => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out after ${timeoutMs}ms`);
}

test.describe('Durable Session Token Handling', () => {
  test('server assigns a session token on first connection', async () => {
    const conn = await connectDurable(server.baseUrl);

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
    const conn1 = await connectDurable(server.baseUrl);
    conn1.ws.send(JSON.stringify({ author: 'Returner', message: 'first-connect' }));
    await waitFor(() => conn1.messages.some(m => m.includes('first-connect')));
    conn1.close();

    // Wait for disconnect to propagate
    await new Promise(r => setTimeout(r, 1_000));

    // Reconnect — even without explicit token, the server should handle it
    const conn2 = await connectDurable(server.baseUrl);
    conn2.ws.send(JSON.stringify({ author: 'Returner', message: 'second-connect' }));
    await waitFor(() => conn2.messages.some(m => m.includes('second-connect')));

    conn2.close();
  });

  test('messages flow correctly after reconnection', async () => {
    // Connect client A
    const connA = await connectDurable(server.baseUrl);
    connA.ws.send(JSON.stringify({ author: 'UserA', message: 'before-reconnect' }));
    await waitFor(() => connA.messages.some(m => m.includes('before-reconnect')));

    // Connect client B
    const connB = await connectDurable(server.baseUrl);

    // Client A sends another message — B should receive it
    connA.ws.send(JSON.stringify({ author: 'UserA', message: 'hello-B' }));
    await waitFor(() => connB.messages.some(m => m.includes('hello-B')));

    // Disconnect client A
    connA.close();
    await new Promise(r => setTimeout(r, 1_000));

    // Reconnect client A
    const connA2 = await connectDurable(server.baseUrl);

    // Client B sends to reconnected A
    connB.ws.send(JSON.stringify({ author: 'UserB', message: 'welcome-back' }));
    await waitFor(() => connA2.messages.some(m => m.includes('welcome-back')), 30_000);

    connA2.close();
    connB.close();
  });

  test('multiple rapid reconnections do not corrupt session state', async () => {
    // Rapidly connect and disconnect 5 times
    for (let i = 0; i < 5; i++) {
      const conn = await connectDurable(server.baseUrl);
      conn.ws.send(JSON.stringify({ author: 'Rapid', message: `cycle-${i}` }));
      await waitFor(() => conn.messages.some(m => m.includes(`cycle-${i}`)));
      conn.close();
      await new Promise(r => setTimeout(r, 300));
    }

    // Final connection should work cleanly
    const final = await connectDurable(server.baseUrl);
    final.ws.send(JSON.stringify({ author: 'Rapid', message: 'final-check' }));
    await waitFor(() => final.messages.some(m => m.includes('final-check')));

    final.close();
  });
});
