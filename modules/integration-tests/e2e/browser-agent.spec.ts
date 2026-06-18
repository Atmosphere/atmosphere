import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-browser-agent']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Browser agent', () => {
  test('console loads with the chat input', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  // Regression for the browser-agent key handling. The sample pins the Cohere
  // runtime; the generic key resolver falls back to LLM_API_KEY / OPENAI_API_KEY
  // / GEMINI_API_KEY, so before the fix a stray non-Cohere key was sent to
  // Cohere and produced a confusing "401 Incorrect API key". The fix checks
  // COHERE_API_KEY directly and renders a clear hint when it is absent.
  //
  // CI runs samples without a COHERE_API_KEY, so this drives the no-key path and
  // asserts the hint renders — and that the leaked-key 401 does NOT.
  test('without a Cohere key, shows the COHERE_API_KEY hint instead of a 401', async ({ page }) => {
    test.skip(
      !!process.env.COHERE_API_KEY,
      'COHERE_API_KEY is set in this environment — the no-key hint path is not exercised',
    );

    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-input').fill("What's the top story on news.ycombinator.com?");
    await page.getByTestId('chat-send').click();

    await expect(page.getByText(/COHERE_API_KEY/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/Incorrect API key|returned 401/i)).toHaveCount(0);
  });
});
