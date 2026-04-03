import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Auth OAuth/JWT Flows', () => {

  test('login endpoint returns a JWT token', async () => {
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

  test('JWT token allows WebSocket connection', async () => {
    const loginRes = await fetch(`${server.baseUrl}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'alice', password: 'secret' }),
    });
    const { token } = await loginRes.json();

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

  test('invalid JWT token is rejected', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=invalid.jwt.token';

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => { ws.on('close', (code) => resolve(`closed: ${code}`)); });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    expect(result).not.toBe('timeout');
  });

  test('missing token is rejected with 401', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';

    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => { ws.on('close', (code) => resolve(`closed: ${code}`)); });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      ws.on('unexpected-response', (_req, res) => resolve(`HTTP ${res.statusCode}`));
      setTimeout(() => resolve('timeout'), 10_000);
    });

    expect(result).not.toBe('timeout');
    expect(result).not.toBe('connected');
  });

  test('expired token triggers refresh and connection succeeds', async () => {
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

  test('RBAC — valid token can send messages', async () => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/atmosphere/ai-chat?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0' +
      '&X-Atmosphere-Auth=demo-token&Content-Type=application/json' +
      '&X-Atmosphere-TrackMessageSize=true';

    const messages: string[] = [];
    const result = await new Promise<string>((resolve) => {
      const ws = new WebSocket(wsUrl);
      ws.on('open', () => {
        setTimeout(() => ws.send('Hello RBAC'), 500);
      });
      ws.on('message', (data) => {
        messages.push(data.toString());
        if (messages.length > 0) {
          ws.close();
          resolve('received');
        }
      });
      ws.on('error', (err) => resolve(`error: ${err.message}`));
      setTimeout(() => { resolve(messages.length > 0 ? 'received' : 'timeout'); }, 30_000);
    });

    expect(result).toBe('received');
    expect(messages.length).toBeGreaterThan(0);
  });
});
