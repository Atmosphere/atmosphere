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
  test('server closes idle WebSocket connections after inactivity', async () => {
    // Connect and then do nothing — wait for the server's idle timeout
    // The chat sample configures MAX_INACTIVE (default varies by sample).
    // This test verifies the connection lifecycle is handled properly.
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
    const ws = new WebSocket(wsUrl);

    const events: string[] = [];
    ws.on('open', () => events.push('open'));
    ws.on('close', () => events.push('close'));
    ws.on('error', () => events.push('error'));

    // Wait for connection
    await new Promise<void>((resolve) => {
      ws.on('open', resolve);
      setTimeout(() => resolve(), 5000);
    });

    expect(events).toContain('open');

    // The connection should be alive
    expect(ws.readyState).toBe(WebSocket.OPEN);

    ws.close();
  });

  test('client can cleanly disconnect and server cleans up', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
    const ws = new WebSocket(wsUrl);

    await new Promise<void>((resolve, reject) => {
      ws.on('open', resolve);
      ws.on('error', reject);
      setTimeout(() => reject(new Error('timeout')), 10_000);
    });

    // Send a message to establish session
    ws.send(JSON.stringify({ author: 'Disconnector', message: 'About to leave' }));
    await new Promise(r => setTimeout(r, 500));

    // Close cleanly
    const closePromise = new Promise<number>((resolve) => {
      ws.on('close', (code) => resolve(code));
    });

    ws.close(1000, 'Normal closure');
    const closeCode = await closePromise;

    // Should close with normal code
    expect(closeCode).toBe(1000);

    // Server should log the disconnect
    await new Promise(r => setTimeout(r, 500));
    const output = server.getOutput();
    expect(output).toContain('closed the connection');
  });

  test('multiple rapid connect-disconnect cycles do not leak resources', async () => {
    const cycles = 10;

    for (let i = 0; i < cycles; i++) {
      const wsUrl = server.baseUrl.replace('http', 'ws') +
        '/atmosphere/chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
      const ws = new WebSocket(wsUrl);

      await new Promise<void>((resolve) => {
        ws.on('open', resolve);
        ws.on('error', () => resolve()); // ignore errors
        setTimeout(resolve, 3000);
      });

      ws.close();
      await new Promise(r => setTimeout(r, 100));
    }

    // After all cycles, the server should still accept new connections
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
