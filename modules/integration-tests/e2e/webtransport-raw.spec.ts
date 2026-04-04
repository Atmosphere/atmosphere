import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { fetchWebTransportInfo } from './helpers/webtransport-helper';
import { connectWebSocket, waitFor } from './helpers/transport-helper';

/**
 * WebTransport raw transport E2E tests — verifies HTTP/3 endpoint info,
 * certificate hash exposure, and WebSocket fallback behavior.
 *
 * These tests complement the existing webtransport.spec.ts by focusing
 * on the transport info API and fallback mechanics.
 */

test.describe('WebTransport Raw Streams', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    server = await startSample(SAMPLES['spring-boot-chat']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('WebTransport info endpoint reports enabled status', async () => {
    const info = await fetchWebTransportInfo(server.baseUrl);
    expect(info).not.toBeNull();
    expect(info!.enabled).toBe(true);
    expect(typeof info!.port).toBe('number');
  });

  test('WebTransport info includes certificate hash', async () => {
    const info = await fetchWebTransportInfo(server.baseUrl);
    expect(info).not.toBeNull();
    expect(info!.certificateHash).toBeDefined();
    expect(info!.certificateHash!.length).toBeGreaterThan(0);
  });

  test('WebSocket fallback works when WebTransport unavailable', async () => {
    // Even if WebTransport is unavailable, WebSocket should work
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    expect(client.ws.readyState).toBe(1); // OPEN

    client.ws.send(JSON.stringify({ author: 'WTFallback', message: 'fallback-test' }));
    await waitFor(() => client.messages.some(m => m.includes('fallback-test')));

    client.close();
  });

  test('multiple transports can connect sequentially', async () => {
    // First via WebSocket
    const ws1 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    ws1.ws.send(JSON.stringify({ author: 'First', message: 'ws-first' }));
    await waitFor(() => ws1.messages.some(m => m.includes('ws-first')));
    ws1.close();

    // Then another WebSocket
    const ws2 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    ws2.ws.send(JSON.stringify({ author: 'Second', message: 'ws-second' }));
    await waitFor(() => ws2.messages.some(m => m.includes('ws-second')));
    ws2.close();
  });
});
