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
 * See ai-streaming-dom.spec.ts for why the `?token=` param is required: the
 * fixture forces ATMOSPHERE_AUTH_ENABLED=true, and without a token the
 * AuthInterceptor closes the console WebSocket immediately after the upgrade.
 */
function consoleUrl(): string {
  return server.baseUrl + '/atmosphere/console/?token=demo-token';
}

/** Send a prompt and wait for that turn's assistant bubble to settle. */
async function sendTurn(page: import('@playwright/test').Page, prompt: string, turn: number) {
  await page.getByTestId('chat-input').fill(prompt);
  await page.getByTestId('chat-send').click();
  await expect(page.locator('.message--user')).toHaveCount(turn, { timeout: 10_000 });
  await expect(page.locator('.message--assistant')).toHaveCount(turn, { timeout: 30_000 });
  await expect(page.locator('.message--assistant').last()).not.toBeEmpty();
}

test.describe('AI Chat — New Features E2E', () => {

  test('capability validation: server starts successfully with requires', async ({ page }) => {
    // If capability validation failed at startup, the server wouldn't be running.
    // The endpoint has requires = {TEXT_STREAMING, SYSTEM_PROMPT} and the built-in
    // support advertises both — so startup should succeed.
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
  });

  test('conversation survives three turns in the same session', async ({ page }) => {
    await page.goto(consoleUrl());
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 30_000 });

    await sendTurn(page, 'Hello', 1);
    await sendTurn(page, 'What is Atmosphere?', 2);
    await sendTurn(page, 'Tell me more', 3);

    // Every turn is still on screen — the transcript is not truncated or reset
    // between rounds, and each prompt got its own answer.
    const userBubbles = page.locator('.message--user');
    await expect(userBubbles.nth(0)).toContainText('Hello');
    await expect(userBubbles.nth(1)).toContainText('What is Atmosphere?');
    await expect(userBubbles.nth(2)).toContainText('Tell me more');
  });

  test('two independent clients maintain separate conversations', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    try {
      const page1 = await ctx1.newPage();
      const page2 = await ctx2.newPage();

      await page1.goto(consoleUrl());
      await page2.goto(consoleUrl());
      await expect(page1.getByText('Connected')).toBeVisible({ timeout: 30_000 });
      await expect(page2.getByText('Connected')).toBeVisible({ timeout: 30_000 });

      await sendTurn(page1, 'Hello from client 1', 1);
      await sendTurn(page2, 'Hello from client 2', 1);

      // Each client sees only its own prompt — the other client's message must
      // never leak into this session's transcript.
      await expect(page1.locator('.message--user')).toHaveCount(1);
      await expect(page1.locator('.message--user').last())
        .toContainText('Hello from client 1');
      await expect(page1.getByText('Hello from client 2')).toHaveCount(0);

      await expect(page2.locator('.message--user')).toHaveCount(1);
      await expect(page2.locator('.message--user').last())
        .toContainText('Hello from client 2');
      await expect(page2.getByText('Hello from client 1')).toHaveCount(0);
    } finally {
      await ctx1.close();
      await ctx2.close();
    }
  });
});
