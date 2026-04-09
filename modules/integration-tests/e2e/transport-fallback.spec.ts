import { test, expect } from '@playwright/test';
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

    await page.goto(server.baseUrl + '/atmosphere/console/');

    // The console should fall back to SSE/long-polling and still connect
    await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 15_000 });

    // Verify the chat still works — send a message and check it appears
    await page.getByTestId('chat-input').fill('Fallback works!');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('message-list')).toContainText('Fallback works!', { timeout: 10_000 });
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

    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 15_000 });

    // Send multiple messages
    await page.getByTestId('chat-input').fill('First message');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('message-list')).toContainText('First message', { timeout: 10_000 });

    await page.getByTestId('chat-input').fill('Second message');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('message-list')).toContainText('Second message', { timeout: 10_000 });

    // Verify input clears after sending
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });
});
