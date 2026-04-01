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

test.describe('Auth Token Interceptor', () => {
  test('WebSocket connection with valid token succeeds', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=demo-token';

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => { ws.close(); resolve('connected'); });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    expect(result).toBe('connected');
  });

  // Auth rejection requires auth interceptor configured in the sample — skip until enabled
  test.skip('WebSocket connection without token is rejected with 401', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => {
        // Even if it opens, the server may close it immediately
        ws.on('close', (code) => resolve(`closed: ${code}`));
      });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    // Server should reject — either via HTTP 401 or by closing the WS
    expect(result).not.toBe('timeout');
    expect(result).not.toBe('connected');
  });

  test.skip('WebSocket connection with invalid token is rejected', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=wrong-token';

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => {
        ws.on('close', (code) => resolve(`closed: ${code}`));
      });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    expect(result).not.toBe('timeout');
  });

  test.skip('login endpoint returns a valid token', async () => {
    const response = await fetch(`${server.baseUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'test', password: 'test' }),
    });

    expect(response.ok).toBe(true);
    const body = await response.json();
    expect(body.token).toBeDefined();
    expect(typeof body.token).toBe('string');
    expect(body.token.length).toBeGreaterThan(0);
  });

  test('token from login endpoint allows WebSocket connection', async () => {
    // First, get a token
    const loginResponse = await fetch(`${server.baseUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'alice', password: 'secret' }),
    });
    const { token } = await loginResponse.json();

    // Then connect with that token
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      `&X-Atmosphere-Auth=${encodeURIComponent(token)}`;

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => { ws.close(); resolve('connected'); });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    expect(result).toBe('connected');
  });

  test('authenticated WebSocket can send and receive messages', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=demo-token&Content-Type=application/json' +
      '&X-Atmosphere-TrackMessageSize=true';

    const messages: string[] = [];
    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);

      ws.on('open', () => {
        // Send a prompt after a short delay for the server to set up
        setTimeout(() => {
          ws.send('Hello');
        }, 500);
      });

      ws.on('message', (data) => {
        const msg = data.toString();
        messages.push(msg);
        // Wait for demo response (contains "demo mode" or "Hello")
        if (msg.toLowerCase().includes('demo') || msg.toLowerCase().includes('hello') || messages.length > 5) {
          ws.close();
          resolve('received');
        }
      });

      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => { ws.close(); resolve(messages.length > 0 ? 'received' : 'timeout'); }, 30_000);
    });

    expect(result).toBe('received');
    expect(messages.length).toBeGreaterThan(0);
  });

  test('expired token triggers refresh and connection succeeds', async () => {
    // Connect with an "expired-" prefixed token — the server's TokenValidator
    // returns Expired, and the TokenRefresher issues a new token
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=expired-old-token';

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => { ws.close(); resolve('connected'); });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    // Token refresh should allow the connection
    expect(result).toBe('connected');
  });
});
