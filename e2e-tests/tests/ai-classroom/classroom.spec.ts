import { test, expect } from '@playwright/test';

/**
 * E2E tests for the AI Classroom sample — multi-room AI chat.
 *
 * The classroom sample features:
 *   - Room selector screen with Math, Code, Science, and General rooms
 *   - Per-room AI tutor persona (Math Tutor, Code Mentor, Science Educator)
 *   - Streaming demo responses via DemoResponseProducer
 */
test.describe('Spring Boot AI Classroom', () => {
  test('page loads with room selector', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('room-selector')).toBeVisible();
  });

  test('joining math room shows classroom layout', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('room-math').click();

    await expect(page.getByTestId('classroom-layout')).toBeVisible();
    await expect(page.getByTestId('room-badge')).toContainText('Math');
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });

  test('student sends question and sees streaming response', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('room-math').click();

    await page.getByTestId('chat-input').fill('What is a prime number?');
    await page.getByTestId('chat-send').click();

    // User message should appear
    await expect(page.getByText('What is a prime number?')).toBeVisible();

    // Math room response includes "Math Tutor"
    await expect(page.getByText('Math Tutor', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('code room shows code mentor response', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('room-code').click();

    await expect(page.getByTestId('room-badge')).toContainText('Code');
    await page.getByTestId('chat-input').fill('How do I write clean code?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('Code Mentor', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('science room shows science educator response', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('room-science').click();

    await expect(page.getByTestId('room-badge')).toContainText('Science');
    await page.getByTestId('chat-input').fill('What is photosynthesis?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('Science Educator', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('send button is disabled when input is empty', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('room-math').click();
    await expect(page.getByTestId('chat-send')).toBeDisabled();
  });

  test('input clears after sending', async ({ page }) => {
    await page.goto('/');
    await page.getByTestId('room-math').click();

    await page.getByTestId('chat-input').fill('Test message');
    await page.getByTestId('chat-send').click();
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });
});
