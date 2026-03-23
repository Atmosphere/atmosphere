import { test, expect } from '@playwright/test';

/**
 * Options for configuring the AI chat test suite.
 */
export interface AiChatSuiteOptions {
  /**
   * Text pattern to look for in the AI streaming response.
   * Defaults to /real-time|demo mode/i which matches all DemoResponseProducers.
   */
  responseIndicator?: string | RegExp;
}

/**
 * Registers the standard AI chat test suite that validates core streaming
 * chat functionality. Call this inside a test.describe() block.
 *
 * These tests work with any AI sample that uses:
 *   - ChatLayout (data-testid="chat-layout")
 *   - ChatInput (data-testid="chat-input", "chat-send")
 *   - useStreaming() hook for AI response streaming
 *   - DemoResponseProducer / DemoEventProducer fallback when no API key
 *
 * Tests:
 *   1. Page loads with AI chat layout
 *   2. Send button is disabled when input is empty
 *   3. Send prompt and receive streaming response
 *   4. Input clears after sending
 *   5. Multi-turn conversation preserves history
 */
export function registerAiChatTests(options?: AiChatSuiteOptions): void {
  const indicator = options?.responseIndicator ?? /real-time|demo mode/i;

  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto('/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto('/atmosphere/console/');
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  test('send prompt and receive streaming response', async ({ page }) => {
    await page.goto('/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is Atmosphere?');
    await page.getByTestId('chat-send').click();

    // User message should appear
    await expect(page.getByText('What is Atmosphere?')).toBeVisible();

    // AI response should appear with expected indicator
    if (typeof indicator === 'string') {
      await expect(page.getByText(indicator, { exact: false }))
        .toBeVisible({ timeout: 30_000 });
    } else {
      await expect(page.getByText(indicator).first())
        .toBeVisible({ timeout: 30_000 });
    }
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto('/atmosphere/console/');
    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  test('multi-turn conversation preserves history', async ({ page }) => {
    await page.goto('/atmosphere/console/');

    // First message
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();

    // Wait for response
    if (typeof indicator === 'string') {
      await expect(page.getByText(indicator, { exact: false }))
        .toBeVisible({ timeout: 30_000 });
    } else {
      await expect(page.getByText(indicator).first())
        .toBeVisible({ timeout: 30_000 });
    }

    // Wait for streaming to finish (input re-enables)
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 15_000 });

    // Second message
    await page.getByTestId('chat-input').fill('Tell me more');
    await page.getByTestId('chat-send').click();

    // Both user messages should still be visible
    await expect(page.getByText('Hello', { exact: true })).toBeVisible();
    await expect(page.getByText('Tell me more')).toBeVisible();
  });
}
