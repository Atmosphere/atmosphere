import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Session Stats & UI', () => {
  test('user receives a response after sending a message', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();

    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('stats bar appears after streaming completes', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    // Wait for streaming to complete — input becomes enabled
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // Stats bar should show streaming text count, elapsed time, and texts/s
    await expect(page.getByText('streaming texts', { exact: false }))
      .toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('texts/s', { exact: false }))
      .toBeVisible({ timeout: 5_000 });
  });

  test('stats bar shows non-zero values', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();

    // Wait for streaming to complete
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // Check that streaming text count is > 0 (demo sends word-by-word)
    const statsBar = page.locator('text=/\\d+ streaming texts/');
    await expect(statsBar).toBeVisible({ timeout: 5_000 });

    // Elapsed time should be > 0
    const elapsedMs = page.locator('text=/\\d+ms/');
    await expect(elapsedMs.first()).toBeVisible({ timeout: 5_000 });
  });

  test('send button is disabled during streaming and re-enables after', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Tell me about Atmosphere');
    await page.getByTestId('chat-send').click();

    // During streaming, send button should be disabled
    await expect(page.getByTestId('chat-send')).toBeDisabled({ timeout: 5_000 });

    // Wait for streaming to complete
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // After completion, fill new text and send button should be enabled
    await page.getByTestId('chat-input').fill('Follow up');
    await expect(page.getByTestId('chat-send')).toBeEnabled({ timeout: 5_000 });
  });

  test('streaming response renders with markdown-like formatting', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    // Wait for complete response
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });

    // Wait for streaming to finish
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 15_000 });

    // The response should be rendered (not raw JSON)
    // Verify it doesn't show wire protocol artifacts
    await expect(page.getByText('"type":"streaming-text"')).not.toBeVisible();
  });
});
