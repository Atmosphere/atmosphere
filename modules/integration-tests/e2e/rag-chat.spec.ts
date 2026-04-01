import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-rag-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('RAG Chat', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  test('@flaky user can send a prompt and receive streaming response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    // User message should appear
    await expect(page.getByText('What is Atmosphere?')).toBeVisible();

    // Should receive a response (demo or real API)
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('@flaky RAG-specific prompt receives a response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Tell me about RAG');
    await page.getByTestId('chat-send').click();

    // Should receive a response (demo or real API)
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('@flaky transport-specific prompt receives a response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('What about WebSocket transports?');
    await page.getByTestId('chat-send').click();

    // Should receive a response (demo or real API)
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('multi-turn conversation preserves history', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');

    // First message
    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });

    // Wait for streaming to finish (input re-enabled)
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 15_000 });

    // Second message
    await page.getByTestId('chat-input').fill('Tell me about RAG');
    await page.getByTestId('chat-send').click();
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });

    // Both user messages should still be visible
    await expect(page.getByText('What is Atmosphere?')).toBeVisible();
    await expect(page.getByText('Tell me about RAG')).toBeVisible();
  });
});
