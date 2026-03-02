import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-otel-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring Boot OTel Chat', () => {
  test('page loads and serves the chat UI', async ({ page }) => {
    await page.goto(server.baseUrl);
    // The OTel sample uses a pre-built static bundle with the same chat UI
    await page.getByTestId('chat-layout').waitFor({ state: 'visible' });
  });

  test('client can connect via WebSocket', async () => {
    const { ws, close } = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    expect(ws.readyState).toBe(1); // OPEN
    close();
  });

  test('messages are broadcast between clients', async () => {
    const client1 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const client2 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    client1.ws.send(JSON.stringify({ author: 'OTel-User', message: 'Traced message!' }));

    await waitFor(() => client2.messages.some(m => m.includes('Traced message!')));

    client1.close();
    client2.close();
  });

  test('server logs indicate tracing is active', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    client.ws.send('Hello with tracing');
    await new Promise(r => setTimeout(r, 1000));

    // The Chat.java @Message handler logs "tracing active"
    const output = server.getOutput();
    expect(output).toContain('tracing active');

    client.close();
  });
});
