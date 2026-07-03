import { test, expect, type Page } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Tests the unified Atmosphere AI Console across multiple samples.
 * Verifies that the shared console (logo, subtitle, connection, messaging)
 * works identically for all samples that use `/atmosphere/ai-chat`.
 */

// The e2e fixture boots spring-boot-ai-chat with ATMOSPHERE_AUTH_ENABLED=true
// (the auth-token/auth-oauth specs need it). With auth enforced, the console
// must present a token or the AuthInterceptor closes every WebSocket at the
// protocol layer (the socket upgrades to 101, then the server sends close 1000
// and the client reconnects forever — which reads as "never connects"). The
// console's resolveAuthToken() (lib/authToken.ts) reads a `?token=` query param
// and sends it as X-Atmosphere-Auth on the WS, so load the console with the
// fixture's demo token. Auth-off samples (rag-chat) ignore it, so this is safe
// for every sample. This is the real fix for the long-standing ai-chat
// exclusion, which had wrongly been blamed on "the WS never connects in CI".
function consoleUrl(server: SampleServer): string {
  return server.baseUrl + '/atmosphere/console/?token=demo-token';
}

/**
 * Wait for the console WebSocket to report "Connected". The handshake can take
 * >15s on a loaded CI runner (heavy sample boot + /api/console/info fetch + WS
 * upgrade + auth), so use a generous timeout — a slow-but-successful connect
 * must not read as a flake.
 */
async function waitForConnected(page: Page): Promise<void> {
  await expect(page.getByText('Connected')).toBeVisible({ timeout: 30_000 });
}

const UNIFIED_SAMPLES = [
  'spring-boot-rag-chat',
  'spring-boot-ai-chat',
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
      await page.goto(consoleUrl(server));
      await expect(page.getByTestId('chat-layout')).toBeVisible();
      await expect(page.getByTestId('chat-input')).toBeVisible();
    });

    test('displays Atmosphere logo', async ({ page }) => {
      await page.goto(consoleUrl(server));
      await expect(page.getByAltText('Atmosphere')).toBeVisible();
    });

    test('shows sample subtitle from /api/console/info', async ({ page }) => {
      await page.goto(consoleUrl(server));
      // Subtitle should appear (fetched from /api/console/info)
      const header = page.locator('header');
      // Wait for subtitle to load (async fetch)
      await expect(header).toContainText(/\w{5,}/, { timeout: 10_000 });
    });

    test('connects via WebSocket and shows Connected', async ({ page }) => {
      await page.goto(consoleUrl(server));
      await waitForConnected(page);
    });

    test('send button is disabled when input is empty', async ({ page }) => {
      await page.goto(consoleUrl(server));
      await expect(page.getByTestId('chat-send')).toBeDisabled();
    });

    test('user can send a message and receive a response', async ({ page }) => {
      await page.goto(consoleUrl(server));
      await waitForConnected(page);

      await page.getByTestId('chat-input').fill('Hello');
      await page.getByTestId('chat-send').click();

      // The user message renders in its own bubble (precise class, not a broad
      // [class*=message] match that also caught the assistant/toolbar).
      await expect(page.locator('.message--user').last()).toContainText('Hello', { timeout: 10_000 });

      // Input clears after send.
      await expect(page.getByTestId('chat-input')).toHaveValue('');

      // The assistant replies in its own bubble. Keyless CI hits the demo
      // runtime, whose reply is deterministic, so a precise selector + 30s
      // (streaming can be slow on a loaded runner) is stable — no @flaky needed.
      const assistant = page.locator('.message--assistant').last();
      await expect(assistant).toBeVisible({ timeout: 30_000 });
      await expect(assistant).not.toBeEmpty();
    });

    test('clear button removes messages', async ({ page }) => {
      await page.goto(consoleUrl(server));
      await waitForConnected(page);

      // Send a message
      await page.getByTestId('chat-input').fill('Test');
      await page.getByTestId('chat-send').click();
      // Target the user bubble — the demo reply can echo the prompt, which would
      // make a bare getByText('Test') match two elements (strict-mode violation).
      await expect(page.locator('.message--user').filter({ hasText: 'Test' })).toBeVisible({ timeout: 10_000 });

      // Clear
      await page.getByRole('button', { name: /clear/i }).click();

      // Should show empty state
      await expect(page.getByText('Start a conversation')).toBeVisible({ timeout: 10_000 });
    });
  });
}
