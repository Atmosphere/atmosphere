import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-mcp-server']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('MCP Server Chat', () => {
  test('WebSocket connects to chat endpoint', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    expect(client.ws.readyState).toBe(1); // OPEN
    client.close();
  });

  // Known issue: @ManagedService chat broadcast doesn't work alongside @Agent(headless) in CI
  test.skip('user can send and receive messages', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    await new Promise(r => setTimeout(r, 500));

    const msg = JSON.stringify({ author: 'Alice', message: 'Hello from MCP!' });
    sender.ws.send(msg);

    await waitFor(
      () => receiver.messages.some(m => m.includes('Hello from MCP!')),
      10_000,
    );

    const received = receiver.messages.find(m => m.includes('Hello from MCP!'));
    expect(received).toBeDefined();

    sender.close();
    receiver.close();
  });

  // Known issue: @ManagedService chat broadcast doesn't work alongside @Agent(headless) in CI
  test.skip('message includes author', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    await new Promise(r => setTimeout(r, 500));

    const msg = JSON.stringify({ author: 'Eve', message: 'MCP chat works!' });
    sender.ws.send(msg);

    await waitFor(
      () => receiver.messages.some(m => m.includes('Eve') && m.includes('MCP chat works!')),
      10_000,
    );

    sender.close();
    receiver.close();
  });

  // Known issue: @ManagedService chat broadcast doesn't work alongside @Agent(headless) in CI
  test.skip('multiple messages delivered in order', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    await new Promise(r => setTimeout(r, 500));

    for (let i = 1; i <= 3; i++) {
      sender.ws.send(JSON.stringify({ author: 'Sender', message: `msg-${i}` }));
      await new Promise(r => setTimeout(r, 100));
    }

    await waitFor(
      () => receiver.messages.filter(m => m.includes('msg-')).length >= 3,
      10_000,
    );

    for (let i = 1; i <= 3; i++) {
      expect(receiver.messages.some(m => m.includes(`msg-${i}`))).toBe(true);
    }

    sender.close();
    receiver.close();
  });
});
