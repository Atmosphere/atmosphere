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

  test('message bubbles display author', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('Charlie');
    await chat.sendMessage('Who am I?');
    await chat.expectMessageFrom('Charlie', 'Who am I?');
  });
});
