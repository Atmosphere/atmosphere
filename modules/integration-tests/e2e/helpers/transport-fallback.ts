import { expect, type Page } from '@playwright/test';

/**
 * Make the Console believe the server runs a WebTransport sidecar that cannot
 * be reached: /api/console/info advertises a port nothing listens on, with a
 * well-formed (44-char base64) but unmatched certificate hash.
 */
export async function advertiseUnreachableWebTransport(page: Page): Promise<void> {
  await page.route('**/api/console/info', async (route) => {
    const response = await route.fetch();
    const info = await response.json();
    info.webTransport = { port: 4499, certificateHash: `${'A'.repeat(43)}=` };
    await route.fulfill({ response, json: info });
  });
}

/**
 * Send `prompt` through the Console and wait for the assistant reply that
 * answers it — the n-th assistant bubble — to contain `expected`, and for the
 * reply to complete. The user's own bubble is a local echo rendered before
 * anything is sent, so only an assistant bubble proves the server answered.
 * The text can arrive whole in a reply's first frame; only its `complete`
 * frame ends the Console's streaming state, so a transport that delivers a
 * reply's first frame and nothing after it fails here.
 */
export async function expectAnswer(page: Page, prompt: string, expected: string | RegExp,
                                   nth: number): Promise<void> {
  await page.getByTestId('chat-input').fill(prompt);
  await page.getByTestId('chat-send').click();
  const answers = page.locator('.message--assistant');
  await expect(answers).toHaveCount(nth, { timeout: 20_000 });
  await expect(answers.nth(nth - 1)).toContainText(expected, { timeout: 20_000 });
  await expect(answers.nth(nth - 1)).not.toContainText('Error:');
  await expect(page.locator('.streaming-indicator'),
    'the reply must complete: its terminal frame reached the Console').toHaveCount(0, { timeout: 20_000 });
}
