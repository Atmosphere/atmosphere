import { test, expect } from '@playwright/test';
import { startDualTransportServer, type DualServer } from './fixtures/dual-transport-server';
import { connectWebSocket, connectSSE, waitFor } from './helpers/transport-helper';
import { GrpcChatClient } from './helpers/grpc-client';

const HTTP_PORT = 8088;
const GRPC_PORT = 9098;

let server: DualServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startDualTransportServer(HTTP_PORT, GRPC_PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Cross-Transport Interop — gRPC + WS + SSE on same topic', () => {

  test('gRPC client sends, WebSocket client receives', async () => {
    const ws = await connectWebSocket(server.baseUrl, '/echo');
    await new Promise(r => setTimeout(r, 1000));

    const grpc = new GrpcChatClient('localhost', GRPC_PORT);
    await grpc.connect();
    await grpc.subscribe('/echo');

    grpc.send('/echo', 'grpc-to-ws-interop');

    await waitFor(() => ws.messages.some(m => m.includes('grpc-to-ws-interop')), 10_000);
    expect(ws.messages.some(m => m.includes('grpc-to-ws-interop'))).toBeTruthy();

    ws.close();
    grpc.close();
  });

  test('WebSocket client sends, gRPC client receives', async () => {
    const grpc = new GrpcChatClient('localhost', GRPC_PORT);
    await grpc.connect();
    await grpc.subscribe('/echo');

    const ws = await connectWebSocket(server.baseUrl, '/echo');
    await new Promise(r => setTimeout(r, 1000));

    ws.ws.send('ws-to-grpc-interop');

    const msg = await grpc.waitForMessage('ws-to-grpc-interop', 10_000);
    expect(msg.payload).toContain('ws-to-grpc-interop');

    ws.close();
    grpc.close();
  });

  test('SSE client receives broadcasts from WebSocket sender', async () => {
    const { messages: sseMessages, close: closeSSE } =
      await connectSSE(server.baseUrl, '/echo');
    await new Promise(r => setTimeout(r, 1000));

    const ws = await connectWebSocket(server.baseUrl, '/echo');
    await new Promise(r => setTimeout(r, 500));

    ws.ws.send('ws-to-sse-interop');

    await waitFor(() => sseMessages.some(m => m.includes('ws-to-sse-interop')), 10_000);
    expect(sseMessages.some(m => m.includes('ws-to-sse-interop'))).toBeTruthy();

    ws.close();
    closeSSE();
  });

  test('gRPC sends, both WS and SSE clients receive', async () => {
    const ws = await connectWebSocket(server.baseUrl, '/echo');
    const { messages: sseMessages, close: closeSSE } =
      await connectSSE(server.baseUrl, '/echo');
    await new Promise(r => setTimeout(r, 1500));

    const grpc = new GrpcChatClient('localhost', GRPC_PORT);
    await grpc.connect();
    await grpc.subscribe('/echo');

    grpc.send('/echo', 'grpc-broadcast-all');

    await waitFor(() => ws.messages.some(m => m.includes('grpc-broadcast-all')), 10_000);
    await waitFor(() => sseMessages.some(m => m.includes('grpc-broadcast-all')), 10_000);

    expect(ws.messages.some(m => m.includes('grpc-broadcast-all'))).toBeTruthy();
    expect(sseMessages.some(m => m.includes('grpc-broadcast-all'))).toBeTruthy();

    ws.close();
    closeSSE();
    grpc.close();
  });

  test('all three transports exchange bidirectionally', async () => {
    // Connect all three transports
    const ws = await connectWebSocket(server.baseUrl, '/echo');
    const { messages: sseMessages, close: closeSSE } =
      await connectSSE(server.baseUrl, '/echo');
    const grpc = new GrpcChatClient('localhost', GRPC_PORT);
    await grpc.connect();
    await grpc.subscribe('/echo');
    await new Promise(r => setTimeout(r, 1500));

    // WebSocket sends
    ws.ws.send('from-ws-bidi');
    await grpc.waitForMessage('from-ws-bidi', 10_000);
    await waitFor(() => sseMessages.some(m => m.includes('from-ws-bidi')), 10_000);

    // gRPC sends
    grpc.send('/echo', 'from-grpc-bidi');
    await waitFor(() => ws.messages.some(m => m.includes('from-grpc-bidi')), 10_000);
    await waitFor(() => sseMessages.some(m => m.includes('from-grpc-bidi')), 10_000);

    ws.close();
    closeSSE();
    grpc.close();
  });
});
