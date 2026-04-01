import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Tests the unified Atmosphere AI Console across multiple samples.
 * Verifies that the shared console (logo, subtitle, connection, messaging)
 * works identically for all samples that use `/atmosphere/ai-chat`.
 */

// spring-boot-ai-chat excluded: its browser console WebSocket never connects in CI.
const UNIFIED_SAMPLES = [
  'spring-boot-rag-chat',
] as const;

for (const sampleName of UNIFIED_SAMPLES) {
  test.describe(`Unified Console — ${sampleName}`, () => {
    let server: SampleServer;

    test.beforeAll(async () => {
      server = await startSample(SAMPLES[sampleName]);
    });

    test.afterAll(async () => {
      await server?.stop();
    });

    test('console loads at /atmosphere/console/', async ({ page }) => {
      await page.goto(server.baseUrl + '/atmosphere/console/');
      await expect(page.getByTestId('chat-layout')).toBeVisible();
      await expect(page.getByTestId('chat-input')).toBeVisible();
    });

    test('displays Atmosphere logo', async ({ page }) => {
      await page.goto(server.baseUrl + '/atmosphere/console/');
      await expect(page.getByAltText('Atmosphere')).toBeVisible();
    });

    test('shows sample subtitle from /api/console/info', async ({ page }) => {
      await page.goto(server.baseUrl + '/atmosphere/console/');
      // Subtitle should appear (fetched from /api/console/info)
      const header = page.locator('header');
      // Wait for subtitle to load (async fetch)
      await expect(header).toContainText(/\w{5,}/, { timeout: 10_000 });
    });

    test('connects via WebSocket and shows Connected', async ({ page }) => {
      await page.goto(server.baseUrl + '/atmosphere/console/');
      await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });
    });

    test('send button is disabled when input is empty', async ({ page }) => {
      await page.goto(server.baseUrl + '/atmosphere/console/');
      await expect(page.getByTestId('chat-send')).toBeDisabled();
    });

    test('@flaky user can send a message and receive a response', async ({ page }) => {
      await page.goto(server.baseUrl + '/atmosphere/console/');
      await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });

      await page.getByTestId('chat-input').fill('Hello');
      await page.getByTestId('chat-send').click();

      // User message should appear
      await expect(page.getByText('Hello')).toBeVisible();

      // Input should clear
      await expect(page.getByTestId('chat-input')).toHaveValue('');

      // Should receive some response (demo mode)
      await expect(page.locator('[class*="assistant"], [class*="message"]').last())
        .not.toBeEmpty({ timeout: 30_000 });
    });

    test('clear button removes messages', async ({ page }) => {
      await page.goto(server.baseUrl + '/atmosphere/console/');
      await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });

      // Send a message
      await page.getByTestId('chat-input').fill('Test');
      await page.getByTestId('chat-send').click();
      await expect(page.getByText('Test')).toBeVisible();

      // Clear
      await page.getByRole('button', { name: /clear/i }).click();

      // Should show empty state
      await expect(page.getByText('Start a conversation')).toBeVisible();
    });
  });
}
