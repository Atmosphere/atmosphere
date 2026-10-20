import { test, expect } from '@playwright/test';
import { ChatPage } from './helpers/chat-page';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Jetty Chat (WAR)', () => {
  test('page loads and connects', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();
  });

  test('user can join and send messages', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('Alice');
    await chat.sendMessage('Hello from Playwright!');
    await chat.expectMessage('Hello from Playwright!');
  });

  test('input clears after sending', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('Bob');
    await chat.sendMessage('test message');
    await expect(chat.input).toHaveValue('');
  });

  test('status bar shows Connected', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();
    await chat.expectStatus('Connected');
  });

  test('message bubbles display author', async ({ page, browser }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();
    const otherCtx = await browser.newContext();
    try {
      const other = new ChatPage(await otherCtx.newPage());
      await other.goto(server.baseUrl);
      await other.waitForConnected();

      // The Console's broadcast dialect sends {author: 'console-xxxx', message}:
      // another member renders it author-prefixed, through the broadcaster.
      await chat.sendMessage('Who am I?');
      await other.expectMessageFrom('console-', 'Who am I?');
      // The broadcaster echoes it to the sender too, which already shows it.
      await page.waitForTimeout(1_000);
      await expect(chat.messageBubbles.filter({ hasText: 'Who am I?' })).toHaveCount(1);
    } finally {
      await otherCtx.close();
    }
  });
});
