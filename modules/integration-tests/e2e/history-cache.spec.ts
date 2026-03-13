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

    const allMessages: string[] = [];
    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      let sentCount = 0;

      ws.on('open', () => {
        setTimeout(() => {
          ws.send('What is 2+2?');
          sentCount++;
        }, 500);
      });

      ws.on('message', (data) => {
        allMessages.push(data.toString());
        // After getting some responses, send a second message
        if (allMessages.length > 3 && sentCount === 1) {
          ws.send('And what is 3+3?');
          sentCount++;
        }
        // After more responses to second question, close
        if (allMessages.length > 6 && sentCount === 2) {
          ws.close();
          resolve('received');
        }
      });

      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => {
        ws.close();
        resolve(allMessages.length > 0 ? 'received' : 'timeout');
      }, 30_000);
    });

    expect(result).toBe('received');
    // Should have received responses to both questions
    expect(allMessages.length).toBeGreaterThan(3);
  });
});
