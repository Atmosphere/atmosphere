import { test, expect } from '@playwright/test';

/**
 * Registers the AI tools test suite that validates tool-calling chat
 * functionality. Call this inside a test.describe() block.
 *
 * These tests work with samples that use:
 *   - ChatLayout (data-testid="chat-layout")
 *   - ChatInput (data-testid="chat-input", "chat-send")
 *   - ToolActivity panel (data-testid="tool-activity")
 *   - DemoResponseProducer with tool simulation (get_city_time, get_weather, etc.)
 *
 * Tests:
 *   1. Page loads with AI chat layout
 *   2. Send button is disabled when input is empty
 *   3. Tool call triggers tool activity panel
 *   4. Weather tool call shows relevant content
 *   5. Input clears after sending
 */
export function registerAiToolsTests(): void {
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

    await page.getByTestId('chat-input').fill('What time is it in Tokyo?');
    await page.getByTestId('chat-send').click();

    // The ToolActivity component should appear
    await expect(page.getByTestId('tool-activity'))
      .toBeVisible({ timeout: 30_000 });
    // Should show the tool name
    await expect(page.getByText('get_city_time', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('weather tool call shows relevant content', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is the weather in Paris?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('get_weather', { exact: false }).first())
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
