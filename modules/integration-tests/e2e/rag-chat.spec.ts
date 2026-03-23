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
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  test('user can send a prompt and receive streaming response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    // User message should appear
    await expect(page.getByText('What is Atmosphere?')).toBeVisible();

    // Demo fallback responds with text containing "real-time"
    await expect(page.getByText('real-time', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('demo mode progress message appears without API key', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();

    // DemoResponseProducer sends progress: "Demo mode — set LLM_API_KEY..."
    await expect(page.getByText('Demo mode')).toBeVisible({ timeout: 15_000 });
  });

  test('RAG-specific prompt returns retrieval context response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Tell me about RAG');
    await page.getByTestId('chat-send').click();

    // DemoResponseProducer RAG branch responds with "enhances LLM responses"
    await expect(page.getByText('enhances LLM responses', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('transport-specific prompt returns transport info', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill('What about WebSocket transports?');
    await page.getByTestId('chat-send').click();

    // DemoResponseProducer transport branch responds with "RAG mode"
    await expect(page.getByText('RAG mode', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('multi-turn conversation preserves history', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');

    // First message
    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('real-time', { exact: false }))
      .toBeVisible({ timeout: 30_000 });

    // Wait for streaming to finish (input re-enabled)
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 15_000 });

    // Second message
    await page.getByTestId('chat-input').fill('Tell me about RAG');
    await page.getByTestId('chat-send').click();
    // Use text unique to the RAG response (not the app subtitle)
    await expect(page.getByText('enhances LLM responses', { exact: false }))
      .toBeVisible({ timeout: 30_000 });

    // Both user messages should still be visible
    await expect(page.getByText('What is Atmosphere?')).toBeVisible();
    await expect(page.getByText('Tell me about RAG')).toBeVisible();
  });
});
