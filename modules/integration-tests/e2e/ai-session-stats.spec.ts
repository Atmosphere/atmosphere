import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { quarantined } from './helpers/quarantine';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
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

/**
 * Send a prompt and wait for the round to actually FINISH.
 *
 * A non-empty assistant bubble means text arrived, not that the round closed:
 * the stats footer is gated on `stats && !isStreaming`, and `stats` is only
 * assigned when the `complete` frame lands. Callers therefore give the footer
 * its own generous timeout rather than treating first text as end-of-round.
 */
async function sendAndSettle(page: import('@playwright/test').Page, prompt: string) {
  await page.getByTestId('chat-input').fill(prompt);
  await page.getByTestId('chat-send').click();
  const assistant = page.locator('.message--assistant').last();
  await expect(assistant).toBeVisible({ timeout: 30_000 });
  await expect(assistant).not.toBeEmpty();
}

test.describe('AI Session Stats & UI', () => {
  // The stats footer (ChatContainer.vue, data-testid="session-stats") renders
  // `<n> tokens · <n>ms · <n.n> tok/s`, gated on `stats && !isStreaming`.
  // `stats` is assigned only on the `complete` frame and only when at least one
  // streaming-text was counted (`streamStartedAt > 0`).
  //
  // Two findings from un-quarantining these: the previous versions asserted
  // "streaming texts" / "texts/s", a vocabulary the Console has never rendered,
  // so they could not have passed even with a working connection; and against
  // this sample the footer appears only intermittently, which is why they stay
  // quarantined below — now with metadata and a lane, rather than a bare skip.
  quarantined({
    owner: 'jfarcand',
    expires: '2026-09-30',
    issue: 'pending',
    reason: 'the session-stats footer renders only intermittently for this sample; '
      + '`stats` is assigned on the complete frame and is observed unset on most runs',
  })('stats bar reports token count and rate after a round @quarantined', async ({ page }) => {
    await page.goto(consoleUrl());
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 30_000 });

    await sendAndSettle(page, 'What is Atmosphere?');

    const stats = page.getByTestId('session-stats');
    await expect(stats).toBeVisible({ timeout: 30_000 });
    await expect(stats).toContainText('tokens');
    await expect(stats).toContainText('tok/s');
  });

  quarantined({
    owner: 'jfarcand',
    expires: '2026-09-30',
    issue: 'pending',
    reason: 'same intermittent session-stats footer as the property above',
  })('stats bar shows a non-zero token count and elapsed time @quarantined', async ({ page }) => {
    await page.goto(consoleUrl());
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 30_000 });

    await sendAndSettle(page, 'Hello');

    // The demo runtime streams word-by-word, so the count must be >= 1 —
    // a literal "0 tokens" would mean the streaming-text path never ran.
    const stats = page.getByTestId('session-stats');
    await expect(stats).toBeVisible({ timeout: 30_000 });
    await expect(stats).toContainText(/[1-9]\d* tokens/);
    await expect(stats).toContainText(/\d+ms/);
  });

  test('streaming response renders as text, not raw wire frames', async ({ page }) => {
    await page.goto(consoleUrl());
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 30_000 });

    await sendAndSettle(page, 'What is Atmosphere?');

    // The console must decode the wire protocol before rendering — a raw
    // envelope leaking into the bubble is the regression this pins.
    await expect(page.locator('.message--assistant').last())
      .not.toContainText('"type":"streaming-text"');
    await expect(page.locator('.message--assistant').last())
      .not.toContainText('"event":"text-delta"');
  });
});
