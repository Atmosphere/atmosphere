import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Large Payload', () => {
  test('100KB text message is received without truncation', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    // Generate a 100KB message
    const largeContent = 'A'.repeat(100_000);
    const msg = JSON.stringify({
      author: 'BigSender',
      message: largeContent,
    });

    sender.ws.send(msg);

    // Receiver should get the full message
    await waitFor(
      () => receiver.messages.some(m => m.includes(largeContent)),
      30_000,
    );

    // Verify the message wasn't truncated
    const received = receiver.messages.find(m => m.includes('BigSender'));
    expect(received).toBeDefined();
    expect(received!.length).toBeGreaterThanOrEqual(largeContent.length);

    sender.close();
    receiver.close();
  });

  test('multiple large messages are delivered in sequence', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    // Send 5 messages of 10KB each with unique markers
    const payloads: string[] = [];
    for (let i = 0; i < 5; i++) {
      const content = `MARKER_${i}_` + 'X'.repeat(10_000);
      payloads.push(content);
      sender.ws.send(JSON.stringify({ author: 'Bulk', message: content }));
      await new Promise(r => setTimeout(r, 100));
    }

    // All 5 should arrive
    await waitFor(
      () => payloads.every(p =>
        receiver.messages.some(m => m.includes(p.substring(0, 20))),
      ),
      30_000,
    );

    // Verify each marker is present
    for (let i = 0; i < 5; i++) {
      expect(receiver.messages.some(m => m.includes(`MARKER_${i}_`))).toBe(true);
    }

    sender.close();
    receiver.close();
  });

  test('large message in browser renders without crashing', async ({ page }) => {
    const { ChatPage } = await import('./helpers/chat-page');
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('LargeUser');

    // Send a moderately large message (5KB) through the UI
    const largeMsg = 'Test_' + 'B'.repeat(5_000);
    await chat.sendMessage(largeMsg);

    // Should render in the message list
    await chat.expectMessage('Test_', { timeout: 10_000 });
  });
});
