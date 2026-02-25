import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-spring-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring AI Chat', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('shows demo mode banner when no API key', async ({ page }) => {
    await page.goto(server.baseUrl);
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();

    // Demo mode banner should appear in the streamed response
    await expect(page.getByText('Demo mode')).toBeVisible({ timeout: 15_000 });
  });

  test('user can send a prompt and receive streaming response', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    // User message should appear
    await expect(page.getByText('What is Atmosphere?')).toBeVisible();

    // Wait for AI response text (demo fallback includes this)
    await expect(page.getByText('real-time', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl);
    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();

    // Input should be cleared after send
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });
});
