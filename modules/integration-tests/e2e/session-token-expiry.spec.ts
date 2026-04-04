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

/** Open an Atmosphere WebSocket connection with optional session token. */
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
    await new Promise(r => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out after ${timeoutMs}ms`);
}

test.describe('Session Token Expiry', () => {

  test('fresh connection works without token', async () => {
    const conn = await connectAtmosphere(server.baseUrl);
    conn.ws.send(JSON.stringify({ author: 'Fresh', message: 'no-token' }));
    await waitFor(() => conn.messages.some(m => m.includes('no-token')));
    conn.close();
  });

  test('expired/invalid token is rejected gracefully', async () => {
    const conn = await connectAtmosphere(server.baseUrl, {
      sessionToken: 'expired-invalid-token-12345',
    });

    // Server should issue a new session — still allows messaging
    conn.ws.send(JSON.stringify({ author: 'Expired', message: 'after-expired-token' }));
    await waitFor(() => conn.messages.some(m => m.includes('after-expired-token')));
    conn.close();
  });

  test('stale token from different server instance', async () => {
    const conn = await connectAtmosphere(server.baseUrl, {
      sessionToken: 'stale-server-instance-token-99999',
    });

    // Should still be able to communicate
    conn.ws.send(JSON.stringify({ author: 'Stale', message: 'stale-token-test' }));
    await waitFor(() => conn.messages.some(m => m.includes('stale-token-test')));
    conn.close();
  });

  test('multiple connections with same token do not conflict', async () => {
    const token = 'shared-test-token-abc';
    const conn1 = await connectAtmosphere(server.baseUrl, { sessionToken: token });
    const conn2 = await connectAtmosphere(server.baseUrl, { sessionToken: token });

    conn1.ws.send(JSON.stringify({ author: 'User1', message: 'shared-token-1' }));
    await waitFor(() => conn2.messages.some(m => m.includes('shared-token-1')));

    conn2.ws.send(JSON.stringify({ author: 'User2', message: 'shared-token-2' }));
    await waitFor(() => conn1.messages.some(m => m.includes('shared-token-2')));

    conn1.close();
    conn2.close();
  });

  test('connection cleanup after disconnect', async () => {
    const conn = await connectAtmosphere(server.baseUrl);
    conn.ws.send(JSON.stringify({ author: 'Cleanup', message: 'before-cleanup' }));
    await waitFor(() => conn.messages.some(m => m.includes('before-cleanup')));
    conn.close();

    // Wait for cleanup to happen
    await new Promise(r => setTimeout(r, 2000));

    // New connection should work fine
    const conn2 = await connectAtmosphere(server.baseUrl);
    conn2.ws.send(JSON.stringify({ author: 'AfterCleanup', message: 'after-cleanup' }));
    await waitFor(() => conn2.messages.some(m => m.includes('after-cleanup')));
    conn2.close();
  });

  test('rapid connect/disconnect cycles do not leak resources', async () => {
    for (let i = 0; i < 10; i++) {
      const conn = await connectAtmosphere(server.baseUrl);
      conn.ws.send(JSON.stringify({ author: 'Cycle', message: `cycle-${i}` }));
      // Don't wait for response — rapid disconnect
      conn.close();
    }

    // After rapid cycles, server should still be responsive
    await new Promise(r => setTimeout(r, 2000));
    const conn = await connectAtmosphere(server.baseUrl);
    conn.ws.send(JSON.stringify({ author: 'AfterCycles', message: 'still-ok' }));
    await waitFor(() => conn.messages.some(m => m.includes('still-ok')));
    conn.close();
  });
});
