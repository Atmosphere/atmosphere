import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-a2a-agent']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Wait for a condition with polling. */
async function waitFor(fn: () => boolean, timeoutMs = 15_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (fn()) return;
    await new Promise(r => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out after ${timeoutMs}ms`);
}

/** Send a JSON-RPC A2A request. */
async function a2aRequest(
  baseUrl: string,
  method: string,
  params: Record<string, unknown> = {},
  id = 1,
): Promise<Record<string, unknown>> {
  const res = await fetch(`${baseUrl}/atmosphere/a2a`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
  });
  return (await res.json()) as Record<string, unknown>;
}

test.describe('Concurrent Protocol Access', () => {

  test('A2A and WebSocket access same agent simultaneously', async () => {
    // Open WebSocket connection
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/a2a?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
    const messages: string[] = [];
    const ws = await new Promise<WebSocket>((resolve, reject) => {
      const socket = new WebSocket(wsUrl);
      socket.on('message', (data) => {
        const text = data.toString().trim();
        if (text) messages.push(text);
      });
      socket.on('open', () => resolve(socket));
      socket.on('error', reject);
      setTimeout(() => reject(new Error('timeout')), 10_000);
    });

    // Simultaneously send A2A JSON-RPC request
    const a2aPromise = a2aRequest(server.baseUrl, 'agent/authenticatedExtendedCard', {}, 1);

    const a2aResult = await a2aPromise;
    expect(a2aResult.result).toBeDefined();
    const result = a2aResult.result as Record<string, unknown>;
    expect(result.name).toBeDefined();

    ws.close();
  });

  test('multiple concurrent A2A requests complete independently', async () => {
    const promises = Array.from({ length: 10 }, (_, i) =>
      a2aRequest(server.baseUrl, 'message/send', {
        message: {
          role: 'user',
          parts: [{ type: 'text', text: `Concurrent request ${i}` }],
          metadata: { skillId: 'ask' },
        },
      }, i + 100),
    );

    const results = await Promise.all(promises);

    for (const body of results) {
      const result = body.result as Record<string, unknown>;
      expect(result).toBeDefined();
      const status = result.status as { state: string };
      expect(status.state).toBe('COMPLETED');
    }
  });

  test('WebSocket and HTTP A2A do not cause resource conflicts', async () => {
    // Open 3 WebSocket connections
    const wsClients: WebSocket[] = [];
    for (let i = 0; i < 3; i++) {
      const wsUrl = server.baseUrl.replace('http', 'ws') +
        '/atmosphere/a2a?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
      const ws = await new Promise<WebSocket>((resolve, reject) => {
        const socket = new WebSocket(wsUrl);
        socket.on('open', () => resolve(socket));
        socket.on('error', reject);
        setTimeout(() => reject(new Error('timeout')), 10_000);
      });
      wsClients.push(ws);
    }

    // While WS connections are open, send HTTP A2A requests
    const httpResults = await Promise.all(
      Array.from({ length: 3 }, (_, i) =>
        a2aRequest(server.baseUrl, 'agent/authenticatedExtendedCard', {}, i + 200),
      ),
    );

    for (const body of httpResults) {
      expect(body.result).toBeDefined();
    }

    // Clean up
    for (const ws of wsClients) ws.close();
  });
});
