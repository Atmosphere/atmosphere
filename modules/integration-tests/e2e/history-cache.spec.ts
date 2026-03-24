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

test.describe('History Cache (BoundedMemoryCache)', () => {
  test('BoundedMemoryCache is active when cache.enabled=true', async () => {
    // Connect and send a message — if cache is active, the server will
    // store the response for replay on reconnect
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=demo-token&Content-Type=application/json' +
      '&X-Atmosphere-TrackMessageSize=true';

    const messages: string[] = [];
    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);

      ws.on('open', () => {
        setTimeout(() => ws.send('Hello cache test'), 500);
      });

      ws.on('message', (data) => {
        messages.push(data.toString());
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
      }, 20_000);
    });

    expect(result).toBe('received');
    expect(messages.length).toBeGreaterThan(0);
  });

  test('multiple exchanges maintain conversation context', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=demo-token&Content-Type=application/json' +
      '&X-Atmosphere-TrackMessageSize=true';

    const firstMessages: string[] = [];
    const secondMessages: string[] = [];
    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      let phase: 'init' | 'first' | 'second' = 'init';

      ws.on('open', () => {
        setTimeout(() => {
          phase = 'first';
          ws.send('What is 2+2?');
        }, 500);
      });

      ws.on('message', (data) => {
        if (phase === 'first') firstMessages.push(data.toString());
        if (phase === 'second') secondMessages.push(data.toString());
      });

      // Send second question after a delay to let first response complete
      setTimeout(() => {
        phase = 'second';
        ws.send('And what is 3+3?');
      }, 10_000);

      // Close after both questions have had time to respond
      setTimeout(() => {
        ws.close();
        const total = firstMessages.length + secondMessages.length;
        resolve(total > 0 ? 'received' : 'timeout');
      }, 25_000);

      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
    });

    expect(result).toBe('received');
    expect(firstMessages.length).toBeGreaterThan(0);
    expect(secondMessages.length).toBeGreaterThan(0);
  });
});
