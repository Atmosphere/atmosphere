import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-adk-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('ADK Chat', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('user can send hello and receive ADK agent response', async ({ page }) => {
    await page.goto(server.baseUrl);
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('Hello')).toBeVisible();

    // DemoEventProducer responds with text containing "ADK agent" for hello
    await expect(page.getByText('ADK agent', { exact: false }))
      .toBeVisible({ timeout: 15_000 });
  });

  test('user can send a prompt and receive streaming response', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Tell me about atmosphere');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('Tell me about atmosphere')).toBeVisible();

    // DemoEventProducer responds with text containing "real-time" for atmosphere
    await expect(page.getByText('real-time', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl);
    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });
});
