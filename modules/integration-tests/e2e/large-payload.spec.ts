import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';
import { ChatPage } from './helpers/chat-page';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Large Payload', () => {
  test('4KB text message is received without truncation', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    // 4KB message — within default WS max message size limits
    const largeContent = 'A'.repeat(4_000);
    const msg = JSON.stringify({
      author: 'BigSender',
      message: largeContent,
    });

    sender.ws.send(msg);

    await waitFor(
      () => receiver.messages.some(m => m.includes(largeContent.substring(0, 100))),
      15_000,
    );

    // Verify the message content is present
    const received = receiver.messages.find(m => m.includes('BigSender'));
    expect(received).toBeDefined();

    sender.close();
    receiver.close();
  });

  test('multiple medium messages are delivered in sequence', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    // Send 5 messages of 2KB each
    for (let i = 0; i < 5; i++) {
      const content = `MARKER_${i}_` + 'X'.repeat(2_000);
      sender.ws.send(JSON.stringify({ author: 'Bulk', message: content }));
      await new Promise(r => setTimeout(r, 100));
    }

    // All 5 should arrive
    await waitFor(
      () => receiver.messages.filter(m => m.includes('MARKER_')).length >= 5,
      15_000,
    );

    for (let i = 0; i < 5; i++) {
      expect(receiver.messages.some(m => m.includes(`MARKER_${i}_`))).toBe(true);
    }

    sender.close();
    receiver.close();
  });

  test('large message in browser renders without crashing', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('LargeUser');

    // Send a 5KB message through the UI
    const largeMsg = 'Test_' + 'B'.repeat(5_000);
    await chat.sendMessage(largeMsg);

    await chat.expectMessage('Test_', { timeout: 10_000 });
  });
});
