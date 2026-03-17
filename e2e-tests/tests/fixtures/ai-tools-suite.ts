import { test, expect } from '@playwright/test';

/**
 * Options for configuring the AI tools test suite.
 */
export interface AiToolsSuiteOptions {
  /** Tool name shown when asking about time in a city. Default: "get_city_time" */
  timeToolName?: string;
  /** Tool name shown when asking about weather. Default: "get_weather" */
  weatherToolName?: string;
}

/**
 * Registers the AI tools test suite that validates tool-calling chat
 * functionality. Call this inside a test.describe() block.
 *
 * These tests work with samples that use:
 *   - ChatLayout (data-testid="chat-layout")
 *   - ChatInput (data-testid="chat-input", "chat-send")
 *   - ToolActivity panel (data-testid="tool-activity")
 *   - DemoResponseProducer/DemoEventProducer with tool event emission
 *
 * Tests:
 *   1. Page loads with AI chat layout
 *   2. Send button is disabled when input is empty
 *   3. Tool call triggers tool activity panel
 *   4. Weather tool call shows relevant content
 *   5. Input clears after sending
 */
export function registerAiToolsTests(options?: AiToolsSuiteOptions): void {
  const timeTool = options?.timeToolName ?? 'get_city_time';
  const weatherTool = options?.weatherToolName ?? 'get_weather';

  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  test('tool call triggers tool activity panel', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is the weather in Tokyo?');
    await page.getByTestId('chat-send').click();

    // The ToolActivity component should appear with the tool name
    await expect(page.getByTestId('tool-activity'))
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(weatherTool, { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('time tool call shows relevant content', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What time is it in Paris?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText(timeTool, { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByText('paris', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });
}
