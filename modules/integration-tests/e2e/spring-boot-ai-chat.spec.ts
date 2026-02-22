import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Spring Boot AI Chat', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('user can send a prompt and receive streaming response', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // Send a simple prompt
    await page.getByTestId('chat-input').fill('Say hello in one word');
    await page.getByTestId('chat-send').click();

    // User message should appear
    await expect(page.getByText('Say hello in one word')).toBeVisible();

    // Wait for an AI response to start streaming (any new text content)
    await expect(page.locator('[data-testid="streaming-message"], .streaming-message, [class*="streaming"]').first())
      .toBeVisible({ timeout: 30_000 });
  });
});
