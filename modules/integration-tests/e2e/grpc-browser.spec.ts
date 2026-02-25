import { test, expect } from '@playwright/test';
import { startDualTransportServer, type DualServer } from './fixtures/dual-transport-server';
import { GrpcChatClient } from './helpers/grpc-client';

const HTTP_PORT = 8087;
const GRPC_PORT = 9097;

let server: DualServer;

test.beforeAll(async () => {
  server = await startDualTransportServer(HTTP_PORT, GRPC_PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('gRPC → Browser Cross-Transport', () => {
  test('gRPC client pushes a message that the browser receives via WebSocket', async ({ page }) => {
    // 1. Open a WebSocket connection in the browser
    await page.goto('about:blank');
    const wsUrl = `${server.wsUrl}/echo?X-Atmosphere-Transport=websocket&X-Atmosphere-tracking-id=browser-1&X-Atmosphere-Framework=5.0.0`;

    await page.evaluate((url) => {
      (window as any).__messages = [];
      (window as any).__connected = false;
      const ws = new WebSocket(url);
      ws.onopen = () => { (window as any).__connected = true; };
      ws.onmessage = (e) => {
        const data = e.data?.trim();
        if (data) (window as any).__messages.push(data);
      };
      (window as any).__ws = ws;
    }, wsUrl);

    // Wait for WebSocket to connect
    await page.waitForFunction(() => (window as any).__connected === true, null, { timeout: 5000 });

    // Let the Atmosphere handshake settle
    await page.waitForTimeout(1000);

    // 2. Connect a gRPC client and subscribe to /echo
    const grpc = new GrpcChatClient('localhost', GRPC_PORT);
    await grpc.connect();
    await grpc.subscribe('/echo');

    // 3. gRPC pushes a message
    grpc.send('/echo', 'hello-from-grpc-to-browser');

    // 4. Browser should receive it via WebSocket
    await page.waitForFunction(
      () => (window as any).__messages.some((m: string) => m.includes('hello-from-grpc-to-browser')),
      null,
      { timeout: 10_000 },
    );

    const messages: string[] = await page.evaluate(() => (window as any).__messages);
    expect(messages.some((m) => m.includes('hello-from-grpc-to-browser'))).toBeTruthy();

    grpc.close();
  });

  test('browser sends a message that the gRPC client receives', async ({ page }) => {
    // 1. Connect gRPC client first
    const grpc = new GrpcChatClient('localhost', GRPC_PORT);
    await grpc.connect();
    await grpc.subscribe('/echo');

    // 2. Open WebSocket in browser
    await page.goto('about:blank');
    const wsUrl = `${server.wsUrl}/echo?X-Atmosphere-Transport=websocket&X-Atmosphere-tracking-id=browser-2&X-Atmosphere-Framework=5.0.0`;

    await page.evaluate((url) => {
      (window as any).__connected = false;
      const ws = new WebSocket(url);
      ws.onopen = () => { (window as any).__connected = true; };
      (window as any).__ws = ws;
    }, wsUrl);

    await page.waitForFunction(() => (window as any).__connected === true, null, { timeout: 5000 });
    await page.waitForTimeout(1000);

    // 3. Browser sends a message via WebSocket
    await page.evaluate(() => {
      (window as any).__ws.send('hello-from-browser-to-grpc');
    });

    // 4. gRPC client should receive it
    const msg = await grpc.waitForMessage('hello-from-browser-to-grpc', 10_000);
    expect(msg.payload).toContain('hello-from-browser-to-grpc');

    grpc.close();
  });

  test('bidirectional: browser and gRPC exchange messages', async ({ page }) => {
    // 1. Connect both
    const grpc = new GrpcChatClient('localhost', GRPC_PORT);
    await grpc.connect();
    await grpc.subscribe('/echo');

    await page.goto('about:blank');
    const wsUrl = `${server.wsUrl}/echo?X-Atmosphere-Transport=websocket&X-Atmosphere-tracking-id=browser-3&X-Atmosphere-Framework=5.0.0`;

    await page.evaluate((url) => {
      (window as any).__messages = [];
      (window as any).__connected = false;
      const ws = new WebSocket(url);
      ws.onopen = () => { (window as any).__connected = true; };
      ws.onmessage = (e) => {
        const data = e.data?.trim();
        if (data) (window as any).__messages.push(data);
      };
      (window as any).__ws = ws;
    }, wsUrl);

    await page.waitForFunction(() => (window as any).__connected === true, null, { timeout: 5000 });
    await page.waitForTimeout(1000);

    // 2. gRPC → Browser
    grpc.send('/echo', 'grpc-says-hello');
    await page.waitForFunction(
      () => (window as any).__messages.some((m: string) => m.includes('grpc-says-hello')),
      null,
      { timeout: 10_000 },
    );

    // 3. Browser → gRPC
    await page.evaluate(() => {
      (window as any).__ws.send('browser-says-hello');
    });
    const msg = await grpc.waitForMessage('browser-says-hello', 10_000);
    expect(msg.payload).toContain('browser-says-hello');

    grpc.close();
  });
});
