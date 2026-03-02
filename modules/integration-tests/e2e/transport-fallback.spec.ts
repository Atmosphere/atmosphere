import { test, expect } from '@playwright/test';
import { ChatPage } from './helpers/chat-page';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Transport Fallback', () => {
  test('falls back to long-polling when WebSocket is blocked', async ({ page }) => {
    // Block all WebSocket upgrade requests
    await page.route('**/*', async (route) => {
      const request = route.request();
      if (request.headers()['upgrade'] === 'websocket' ||
          request.url().includes('X-Atmosphere-Transport=websocket')) {
        await route.abort('connectionrefused');
      } else {
        await route.continue();
      }
    });

    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);

    // The app should fall back to long-polling and still connect
    await chat.waitForConnected();

    // Verify the chat still works
    await chat.joinAs('FallbackUser');
    await chat.sendMessage('Fallback works!');
    await chat.expectMessage('Fallback works!');
  });

  test('chat functions normally over fallback transport', async ({ page }) => {
    // Block WebSocket upgrades
    await page.route('**/*', async (route) => {
      const request = route.request();
      if (request.headers()['upgrade'] === 'websocket' ||
          request.url().includes('X-Atmosphere-Transport=websocket')) {
        await route.abort('connectionrefused');
      } else {
        await route.continue();
      }
    });

    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    // Send multiple messages
    await chat.joinAs('Alice');
    await chat.sendMessage('First message');
    await chat.expectMessage('First message');

    await chat.sendMessage('Second message');
    await chat.expectMessage('Second message');

    // Verify input clears
    await expect(chat.input).toHaveValue('');
  });
});
