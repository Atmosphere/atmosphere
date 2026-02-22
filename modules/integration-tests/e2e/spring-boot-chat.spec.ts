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

test.describe('Spring Boot Chat', () => {
  test('page loads and connects', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();
  });

  test('user can join and send messages', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    // First message sets the username (join flow)
    await chat.joinAs('Alice');

    // Send a chat message
    await chat.sendMessage('Hello from Playwright!');

    // The message should appear in the chat
    await chat.expectMessage('Hello from Playwright!');
  });

  test('input clears after sending', async ({ page }) => {
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();

    await chat.joinAs('Bob');
    await chat.sendMessage('test message');

    // Input should be cleared
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

    // Should see the author name on the bubble
    await chat.expectMessageFrom('Charlie', 'Who am I?');
  });
});
