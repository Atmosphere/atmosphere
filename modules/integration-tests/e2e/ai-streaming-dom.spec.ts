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
  test('streaming tokens appear incrementally in the page', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // Send a prompt
    await page.getByTestId('chat-input').fill('Tell me about Atmosphere');
    await page.getByTestId('chat-send').click();

    // The user's message should appear immediately
    await expect(page.getByText('Tell me about Atmosphere')).toBeVisible();

    // Wait for streaming to start — in demo mode, the response contains
    // "real-time" and other demo text that appears token by token
    // We check that content grows over time (streaming, not a single chunk)
    const initialLength = await page.evaluate(() => document.body.innerText.length);

    // Wait a bit for tokens to start arriving
    await page.waitForTimeout(1000);

    const midLength = await page.evaluate(() => document.body.innerText.length);

    // Content should have grown as tokens arrive
    expect(midLength).toBeGreaterThan(initialLength);

    // Wait for streaming to complete
    await expect(page.getByText('real-time', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
  });

  test('send button is disabled during streaming', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is WebSocket?');
    await page.getByTestId('chat-send').click();

    // During streaming, the send button should be disabled
    // (the UI disables it while isStreaming === true)
    await expect(page.getByTestId('chat-send')).toBeDisabled({ timeout: 5_000 });

    // After streaming completes, send button should re-enable
    // (it's disabled when input is empty, so we fill something first)
    await expect(page.getByText('real-time', { exact: false }))
      .toBeVisible({ timeout: 30_000 });
    await page.getByTestId('chat-input').fill('Follow-up');
    await expect(page.getByTestId('chat-send')).toBeEnabled({ timeout: 10_000 });
  });

  test('multiple prompts produce separate message blocks', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // First prompt
    await page.getByTestId('chat-input').fill('Hello');
    await page.getByTestId('chat-send').click();
    await expect(page.getByText('real-time', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });

    // Second prompt
    await page.getByTestId('chat-input').fill('What is SSE?');
    await page.getByTestId('chat-send').click();

    // Should see user's second prompt appear
    await expect(page.getByText('What is SSE?')).toBeVisible();

    // Wait for second streaming response
    // Count the number of "real-time" occurrences — should have at least 2
    // (one from each demo response)
    await page.waitForTimeout(5000);
    const responseCount = await page.evaluate(() => {
      return (document.body.innerText.match(/real-time/gi) || []).length;
    });
    expect(responseCount).toBeGreaterThanOrEqual(2);
  });
});
