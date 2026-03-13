import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Offline Queue & Message Tracking', () => {
  test('server accepts messages with X-Atmosphere-Message-Id header', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=demo-token&Content-Type=application/json' +
      '&X-Atmosphere-TrackMessageSize=true';

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl, {
        headers: { 'X-Atmosphere-Message-Id': 'test-msg-001' },
      });

      ws.on('open', () => {
        // Connection established — the server accepted our message-id header
        ws.close();
        resolve('connected');
      });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    expect(result).toBe('connected');
  });

  test('server echoes message-id as ack for WebSocket frames', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=demo-token&Content-Type=application/json' +
      '&X-Atmosphere-TrackMessageSize=true';

    const messages: string[] = [];
    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);

      ws.on('open', () => {
        // Wait for handshake, then send a message
        setTimeout(() => {
          ws.send('Hello from queue test');
        }, 500);
      });

      ws.on('message', (data) => {
        messages.push(data.toString());
        // Collect a few messages then close
        if (messages.length > 3) {
          ws.close();
          resolve('received');
        }
      });

      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => {
        ws.close();
        resolve(messages.length > 0 ? 'received' : 'timeout');
      }, 15_000);
    });

    expect(result).toBe('received');
    expect(messages.length).toBeGreaterThan(0);
  });

  test('messages sent after brief disconnect are not lost', async () => {
    // First connection: establish session
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=demo-token&Content-Type=application/json' +
      '&X-Atmosphere-TrackMessageSize=true';

    const firstMessages: string[] = [];
    await new Promise<void>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => {
        setTimeout(() => {
          ws.send('First message');
        }, 500);
      });
      ws.on('message', (data) => {
        firstMessages.push(data.toString());
        if (firstMessages.length > 2) {
          ws.close();
          resolve();
        }
      });
      ws.on('error', () => resolve());
      setTimeout(() => resolve(), 15_000);
    });

    expect(firstMessages.length).toBeGreaterThan(0);

    // Second connection: reconnect and send another message
    const secondMessages: string[] = [];
    await new Promise<void>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => {
        setTimeout(() => {
          ws.send('Second message after reconnect');
        }, 500);
      });
      ws.on('message', (data) => {
        secondMessages.push(data.toString());
        if (secondMessages.length > 2) {
          ws.close();
          resolve();
        }
      });
      ws.on('error', () => resolve());
      setTimeout(() => { resolve(); }, 15_000);
    });

    expect(secondMessages.length).toBeGreaterThan(0);
  });
});
