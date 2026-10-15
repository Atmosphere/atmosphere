import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { expectAnswer } from './helpers/transport-fallback';

let server: SampleServer;

test.beforeAll(async () => {
  // Use dentist-agent — no auth required, @Agent accepts raw text natively.
  // spring-boot-chat's @ManagedService expects {author,message} JSON,
  // spring-boot-ai-chat requires auth token.
  server = await startSample(SAMPLES['spring-boot-dentist-agent']);
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
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });

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
    await expect(page.getByTestId('status-label')).toHaveText(/^Connected/, { timeout: 15_000 });

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

  test('slash commands are answered over long-polling, every one of them', async ({ page }) => {
    // page.route does not see WebSocket upgrades: without the WebSocket API the
    // Console takes its long-polling fallback.
    await page.addInitScript(() => {
      delete (window as { WebSocket?: unknown }).WebSocket;
    });
    const notLongPolling: string[] = [];
    page.on('request', (request) => {
      const url = new URL(request.url());
      if (url.pathname !== '/atmosphere/agent/dentist') return;
      const transport = url.searchParams.get('X-Atmosphere-Transport')
        ?? request.headers()['x-atmosphere-transport'];
      if (transport !== 'long-polling') {
        notLongPolling.push(`${request.method()} ${request.url()}`);
      }
    });

    await page.goto(server.baseUrl + '/atmosphere/console/');
    const badge = page.getByTestId('atmosphere-connection-status');
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 20_000 });
    await expect(badge).toHaveAttribute('data-transport', 'long-polling');

    // A command's answer reaches the client's poll, not the POST's own
    // response; a repeated command is answered again, as a reply of its own.
    await expectAnswer(page, '/help', 'Available commands', 1);
    await expectAnswer(page, '/pain', 'Pain Management', 2);
    await expectAnswer(page, '/help', 'Available commands', 3);
    expect(notLongPolling, 'every request of the agent must ride long-polling').toEqual([]);
  });
});
