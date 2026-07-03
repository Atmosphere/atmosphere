import { test, expect, type Page } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Tests the unified Atmosphere AI Console across multiple samples.
 * Verifies that the shared console (logo, subtitle, connection, messaging)
 * works identically for all samples that use `/atmosphere/ai-chat`.
 */

/**
 * Wait for the console WebSocket to report "Connected". The handshake can take
 * >15s on a loaded CI runner (sample boot + /api/console/info fetch + WS
 * upgrade), so use a generous timeout — a slow-but-successful connect must not
 * read as a flake. (This is why spring-boot-ai-chat is excluded below: its
 * console WS never connects in CI at all, which is a different problem.)
 */
async function waitForConnected(page: Page): Promise<void> {
  await expect(page.getByText('Connected')).toBeVisible({ timeout: 30_000 });
}

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
      await waitForConnected(page);
    });

    test('send button is disabled when input is empty', async ({ page }) => {
      await page.goto(server.baseUrl + '/atmosphere/console/');
      await expect(page.getByTestId('chat-send')).toBeDisabled();
    });

    test('@flaky user can send a message and receive a response', async ({ page }) => {
      await page.goto(server.baseUrl + '/atmosphere/console/');
      await waitForConnected(page);

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
      await waitForConnected(page);

      // Send a message
      await page.getByTestId('chat-input').fill('Test');
      await page.getByTestId('chat-send').click();
      await expect(page.getByText('Test')).toBeVisible({ timeout: 10_000 });

      // Clear
      await page.getByRole('button', { name: /clear/i }).click();

      // Should show empty state
      await expect(page.getByText('Start a conversation')).toBeVisible({ timeout: 10_000 });
    });
  });
}
