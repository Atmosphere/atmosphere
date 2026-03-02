import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

/**
 * Helper to connect to the durable sessions endpoint.
 */
function connectDurable(
  baseUrl: string,
): Promise<{ ws: WebSocket; messages: string[]; close: () => void }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http', 'ws') + '/atmosphere/chat';
    const ws = new WebSocket(wsUrl);
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
    const { ws, messages, close } = await connectDurable(server.baseUrl);

    ws.send(JSON.stringify({ author: 'Identity', message: 'hello-durable' }));
    await waitFor(() => messages.some(m => m.includes('hello-durable')));

    close();
  });

  test('two independent connections have separate sessions', async () => {
    const conn1 = await connectDurable(server.baseUrl);
    const conn2 = await connectDurable(server.baseUrl);

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

  test('session state survives server restart', async () => {
    test.setTimeout(120_000);

    const conn1 = await connectDurable(server.baseUrl);
    conn1.ws.send(JSON.stringify({ author: 'Persistent', message: 'before-restart' }));
    await waitFor(() => conn1.messages.some(m => m.includes('before-restart')));
    conn1.close();

    await server.restart();

    const conn2 = await connectDurable(server.baseUrl);
    conn2.ws.send(JSON.stringify({ author: 'Persistent', message: 'after-restart' }));
    await waitFor(() => conn2.messages.some(m => m.includes('after-restart')));

    conn2.close();
  });

  test('disconnect and reconnect on same server preserves broadcaster', async () => {
    const conn1 = await connectDurable(server.baseUrl);
    conn1.ws.send(JSON.stringify({ author: 'Returner', message: 'first-visit' }));
    await waitFor(() => conn1.messages.some(m => m.includes('first-visit')));
    conn1.close();

    await new Promise(r => setTimeout(r, 1000));

    const conn2 = await connectDurable(server.baseUrl);
    conn2.ws.send(JSON.stringify({ author: 'Returner', message: 'second-visit' }));
    await waitFor(() => conn2.messages.some(m => m.includes('second-visit')));

    // Verify broadcaster still works with a third client
    const conn3 = await connectDurable(server.baseUrl);
    conn2.ws.send(JSON.stringify({ author: 'Returner', message: 'broadcast-check' }));
    await waitFor(() => conn3.messages.some(m => m.includes('broadcast-check')));

    conn2.close();
    conn3.close();
  });
});
