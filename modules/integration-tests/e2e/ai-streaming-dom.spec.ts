import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Streaming in DOM', () => {
  // Known issue: spring-boot-ai-chat browser console WebSocket never connects in CI
  test.skip('streaming response appears after sending a prompt', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    // Wait for WebSocket to connect — textarea is disabled until connected
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // Send a prompt
    await page.getByTestId('chat-input').fill('Tell me about Atmosphere');
    await page.getByTestId('chat-send').click();

    // The user's message should appear immediately
    await expect(page.getByText('Tell me about Atmosphere')).toBeVisible();

    // Wait for streaming response to complete
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  // Known issue: spring-boot-ai-chat browser console WebSocket never connects in CI
  test.skip('send button is disabled during streaming', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is WebSocket?');
    await page.getByTestId('chat-send').click();

    // During streaming, the send button should be disabled
    await expect(page.getByTestId('chat-send')).toBeDisabled({ timeout: 5_000 });

    // After streaming completes, fill input so send button re-enables
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
    await page.getByTestId('chat-input').fill('Follow-up');
    await expect(page.getByTestId('chat-send')).toBeEnabled({ timeout: 10_000 });
  });

  // Known issue: spring-boot-ai-chat browser console WebSocket never connects in CI
  test.skip('user prompt is visible in the chat after sending', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello AI');
    await page.getByTestId('chat-send').click();

    // Prompt text should appear in the page
    await expect(page.getByText('Hello AI')).toBeVisible();

    // Input should be cleared
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });
});
