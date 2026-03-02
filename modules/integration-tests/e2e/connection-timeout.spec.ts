import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Connection Timeout', () => {
  test('idle WebSocket connection stays open initially', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
    const ws = new WebSocket(wsUrl);

    await new Promise<void>((resolve, reject) => {
      ws.on('open', resolve);
      ws.on('error', reject);
      setTimeout(() => reject(new Error('timeout')), 10_000);
    });

    expect(ws.readyState).toBe(WebSocket.OPEN);
    ws.close();
  });

  test('client can cleanly disconnect', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
    const ws = new WebSocket(wsUrl);

    await new Promise<void>((resolve, reject) => {
      ws.on('open', resolve);
      ws.on('error', reject);
      setTimeout(() => reject(new Error('timeout')), 10_000);
    });

    // Send a message then close cleanly
    ws.send(JSON.stringify({ author: 'Disconnector', message: 'Goodbye' }));
    await new Promise(r => setTimeout(r, 500));

    const closePromise = new Promise<number>((resolve) => {
      ws.on('close', (code) => resolve(code));
    });

    ws.close(1000, 'Normal closure');
    const closeCode = await closePromise;

    expect(closeCode).toBe(1000);
  });

  test('multiple rapid connect-disconnect cycles do not leak resources', async () => {
    const cycles = 10;

    for (let i = 0; i < cycles; i++) {
      const wsUrl = server.baseUrl.replace('http', 'ws') +
        '/atmosphere/chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
      const ws = new WebSocket(wsUrl);

      await new Promise<void>((resolve) => {
        ws.on('open', resolve);
        ws.on('error', () => resolve());
        setTimeout(resolve, 3000);
      });

      ws.close();
      await new Promise(r => setTimeout(r, 100));
    }

    // After all cycles, verify server still accepts connections
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
    const ws = new WebSocket(wsUrl);

    await new Promise<void>((resolve, reject) => {
      ws.on('open', resolve);
      ws.on('error', reject);
      setTimeout(() => reject(new Error('Server stopped accepting connections')), 10_000);
    });

    expect(ws.readyState).toBe(WebSocket.OPEN);
    ws.close();
  });
});
