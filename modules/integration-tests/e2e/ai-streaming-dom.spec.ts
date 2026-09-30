import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * The e2e fixture boots spring-boot-ai-chat with ATMOSPHERE_AUTH_ENABLED=true,
 * so the console must present the demo token or the AuthInterceptor closes
 * every WebSocket right after the 101 upgrade — which reads as "never
 * connects". The console's resolveAuthToken() (lib/authToken.ts) picks the
 * token up from a `?token=` query param, which is what unified-console.spec.ts
 * already relies on. These tests were quarantined under a "WebSocket never
 * connects in CI" comment that misattributed the auth close to the harness.
 */
function consoleUrl(): string {
  return server.baseUrl + '/atmosphere/console/?token=demo-token';
}

test.describe('AI Streaming in DOM', () => {
  test('streaming response appears after sending a prompt', async ({ page }) => {
    await page.goto(consoleUrl());
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('Tell me about Atmosphere');
    await page.getByTestId('chat-send').click();

    // The user's message renders in its own bubble immediately. A precise
    // class avoids a strict-mode violation when the demo reply echoes it.
    await expect(page.locator('.message--user').last())
      .toContainText('Tell me about Atmosphere', { timeout: 10_000 });

    // The assistant bubble streams in and ends up non-empty.
    const assistant = page.locator('.message--assistant').last();
    await expect(assistant).toBeVisible({ timeout: 30_000 });
    await expect(assistant).not.toBeEmpty();
  });

  test('user prompt is visible in the chat after sending', async ({ page }) => {
    await page.goto(consoleUrl());
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('Hello AI');
    await page.getByTestId('chat-send').click();

    await expect(page.locator('.message--user').last())
      .toContainText('Hello AI', { timeout: 10_000 });

    // Input is cleared by the send handler.
    await expect(page.getByTestId('chat-input')).toHaveValue('');
  });

  // ChatInput.vue disables send on `!input.trim() || disabled || isStreaming`.
  // Right after a send the input is empty, so a bare toBeDisabled() would pass on
  // the empty-input gate alone and prove nothing about streaming. Typing ahead
  // while the reply streams leaves the input non-empty: only the streaming gate
  // can then hold the button disabled. The demo runtime streams word-by-word
  // (30 ms per word), so the round lasts long enough to observe.
  test('send button is disabled during streaming', async ({ page }) => {
    await page.goto(consoleUrl());
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 30_000 });

    const input = page.getByTestId('chat-input');
    const send = page.getByTestId('chat-send');
    await input.fill('What is WebSocket?');
    await send.click();

    await input.fill('Follow-up');
    await expect(send).toBeDisabled({ timeout: 5_000 });

    await expect(page.locator('.message--assistant').last())
      .not.toBeEmpty({ timeout: 30_000 });
    await expect(send).toBeEnabled({ timeout: 30_000 });
  });
});
