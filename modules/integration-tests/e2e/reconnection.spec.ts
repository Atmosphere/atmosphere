import { test, expect } from '@playwright/test';
import { ChatPage } from './helpers/chat-page';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Auto-Reconnection', () => {
  test('client reconnects after server restart', async ({ page }) => {
    test.setTimeout(120_000);

    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    // Send a message to verify initial connection works
    await chat.joinAs('Reconnector');
    await chat.sendMessage('Before restart');
    await chat.expectMessage('Before restart');

    // Restart the server
    await server.restart();

    // Wait for atmosphere.js to detect the disconnect and reconnect
    // The app is configured with reconnect: true, reconnectInterval: 5000
    await chat.waitForConnected();

    // Send a message to verify the reconnected session works
    await chat.sendMessage('After restart');
    await chat.expectMessage('After restart', { timeout: 15_000 });
  });

  test('status indicator reflects disconnect and reconnect', async ({ page }) => {
    test.setTimeout(120_000);

    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();
    await chat.expectStatus('Connected');

    // Restart the server — client should detect disconnect
    await server.restart();

    // After reconnection, status should return to Connected
    await chat.waitForConnected();
    await chat.expectStatus('Connected');
  });
});
