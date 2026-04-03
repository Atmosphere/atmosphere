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

test.describe('Durable Session Restart', () => {

  test('session survives full server restart', async () => {
    // Connect and establish session
    const conn1 = await connectAtmosphere(server.baseUrl);
    conn1.ws.send(JSON.stringify({ author: 'Survivor', message: 'Before restart' }));
    await waitFor(() => conn1.messages.some(m => m.includes('Before restart')));
    conn1.close();

    // Restart the server
    await server.restart();

    // Reconnect after restart
    const conn2 = await connectAtmosphere(server.baseUrl);
    conn2.ws.send(JSON.stringify({ author: 'Survivor', message: 'After restart' }));
    await waitFor(() => conn2.messages.some(m => m.includes('After restart')));
    conn2.close();
  });

  test('broadcast resumes after restart', async () => {
    const clientA = await connectAtmosphere(server.baseUrl);
    const clientB = await connectAtmosphere(server.baseUrl);

    // Verify broadcast works before restart
    clientA.ws.send(JSON.stringify({ author: 'A', message: 'pre-restart-msg' }));
    await waitFor(() => clientB.messages.some(m => m.includes('pre-restart-msg')));

    clientA.close();
    clientB.close();

    // Restart
    await server.restart();

    // Reconnect both clients
    const clientA2 = await connectAtmosphere(server.baseUrl);
    const clientB2 = await connectAtmosphere(server.baseUrl);
    await new Promise(r => setTimeout(r, 500));

    // Verify broadcast works after restart
    clientA2.ws.send(JSON.stringify({ author: 'A', message: 'post-restart-msg' }));
    await waitFor(() => clientB2.messages.some(m => m.includes('post-restart-msg')));

    clientA2.close();
    clientB2.close();
  });

  test('stale session token handled gracefully after restart', async () => {
    const conn1 = await connectAtmosphere(server.baseUrl);
    conn1.ws.send(JSON.stringify({ author: 'Token', message: 'original' }));
    await waitFor(() => conn1.messages.some(m => m.includes('original')));
    conn1.close();

    // Restart server — old session state is lost
    await server.restart();

    // Connect with a stale token — server should issue new session
    const conn2 = await connectAtmosphere(server.baseUrl, {
      sessionToken: 'stale-token-from-previous-run',
    });
    conn2.ws.send(JSON.stringify({ author: 'Token', message: 'after stale token' }));
    await waitFor(() => conn2.messages.some(m => m.includes('after stale token')));
    conn2.close();
  });

  test('multiple restarts in sequence work correctly', async () => {
    for (let i = 1; i <= 3; i++) {
      const conn = await connectAtmosphere(server.baseUrl);
      conn.ws.send(JSON.stringify({ author: 'Loop', message: `restart-${i}` }));
      await waitFor(() => conn.messages.some(m => m.includes(`restart-${i}`)));
      conn.close();

      if (i < 3) {
        await server.restart();
      }
    }
  });
});
