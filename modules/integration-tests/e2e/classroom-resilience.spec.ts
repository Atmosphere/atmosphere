import { test, expect, type Page } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { hideWebTransport, installNetworkSwitch } from './helpers/network-switch';

/**
 * End-to-end coverage for the resilience surface of the
 * spring-boot-ai-classroom sample.
 *
 * The classroom's UI is the bundled Atmosphere Console: `console-endpoints`
 * renders a Math / Code / Science room picker, and each room is its own
 * {@code @AiEndpoint}. (The spec used to drive a bespoke `room-math` React
 * page with a `useStreaming` retrofit; that page is gone.) Two contracts are
 * pinned against what the page serves:
 *
 *   1. **Presence** — the server's {@code @Ready} / {@code @Disconnect}
 *      hooks broadcast {@code {"type":"presence","count":N}} on the room's
 *      broadcaster; the Console renders the count as the
 *      {@code data-testid="presence-count"} chip, and it follows a second
 *      student in and back out.
 *
 *   2. **Offline queue** — a question typed while the network is down lands
 *      in the Console's offline queue ({@code data-testid="offline-queue-size"}),
 *      drains on reconnect, and is actually answered: an AI endpoint, not
 *      only the chat-room dialect, survives the round trip.
 *
 *   3. **Reload in a room** — a student who reloads the page while the room's
 *      answer streams comes back to the same room, not to the picker, so the
 *      reattach to the room's run happens there, and the answer completes
 *      once.
 */
/** A real model answers in its own words; keyless, the sample's demo persona answers. */
const REAL_LLM = (process.env.LLM_MODE ?? '').startsWith('real-');

test.describe('Classroom resilience (presence + offline queue)', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(180_000);
    server = await startSample(SAMPLES['spring-boot-ai-classroom']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  async function joinRoom(page: Page, room: string) {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId(`pick-${room}`).click();
    await expect(page.getByTestId('atmosphere-connection-status'))
      .toHaveAttribute('data-phase', 'open', { timeout: 30_000 });
  }

  test('presence chip follows a second student in and out of the math room', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    try {
      const first = await ctx1.newPage();
      const second = await ctx2.newPage();
      await hideWebTransport(first);
      await hideWebTransport(second);

      await joinRoom(first, 'math');
      // @Ready broadcasts presence on connect, so the first frame lands within seconds.
      const presence = first.getByTestId('presence-count');
      await expect(presence).toBeVisible({ timeout: 15_000 });
      await expect(presence).toHaveText(/^\s*1 online\s*$/);

      await joinRoom(second, 'math');
      await expect(presence).toHaveText(/^\s*2 online\s*$/, { timeout: 15_000 });

      // @Disconnect broadcasts the reduced count to the students still there.
      await ctx2.close();
      await expect(presence).toHaveText(/^\s*1 online\s*$/, { timeout: 15_000 });
    } finally {
      await ctx1.close();
      await ctx2.close().catch(() => { /* already closed */ });
    }
  });

  test('a question typed offline queues, drains on reconnect, and is answered', async ({ page, context }) => {
    const net = await installNetworkSwitch(page, context, /\/atmosphere\/classroom\//);
    await joinRoom(page, 'code');
    const badge = page.getByTestId('atmosphere-connection-status');

    await net.down();
    await expect(badge).not.toHaveAttribute('data-phase', 'open', { timeout: 15_000 });

    await page.getByTestId('chat-input').fill('what is a closure?');
    await page.getByTestId('chat-send').click();

    const queueChip = page.getByTestId('offline-queue-size');
    await expect(queueChip).toBeVisible();
    await expect(queueChip).toHaveText(/1 queued/);
    // Nothing can answer while the network is down.
    await expect(page.locator('.message--assistant')).toHaveCount(0);

    await net.up();
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 30_000 });
    await expect(queueChip).not.toBeVisible({ timeout: 15_000 });

    const answer = page.locator('.message--assistant').last();
    await expect(answer).toBeVisible({ timeout: 30_000 });
    // The Console renders an AI `error` frame into this same assistant bubble
    // as "**Error:** …", so a visible, non-empty bubble alone would read a
    // failed drain as an answer.
    await expect(answer).not.toContainText('Error:');
    if (!REAL_LLM) {
      // Keyless, the sample's DemoResponseProducer answers from the room's
      // persona: the Code Mentor reply proves the drained question reached the
      // code room's @AiEndpoint and was answered there, not merely acknowledged.
      await expect(answer).toContainText('Code Mentor', { timeout: 30_000 });
    }
  });

  test('a student reloading mid-answer comes back to the same room and the answer completes once', async ({ page }) => {
    await hideWebTransport(page);
    await joinRoom(page, 'math');
    await page.getByTestId('chat-input').fill('what is a prime number?');
    await page.getByTestId('chat-send').click();
    // Reload as soon as the answer starts streaming.
    await expect(page.locator('.message--assistant').last()).toBeVisible({ timeout: 30_000 });
    await page.reload();

    // The tab comes back to the math room by itself: no picker, connected.
    await expect(page.getByTestId('endpoint-picker')).toHaveCount(0);
    await expect(page.getByTestId('room-bar')).toContainText('Math');
    await expect(page.getByTestId('atmosphere-connection-status'))
      .toHaveAttribute('data-phase', 'open', { timeout: 30_000 });

    // The reattach replays the room's run, which completes: one answer, the
    // whole of it, and the chat takes the next question.
    const answers = page.locator('.message--assistant');
    await expect(answers).toHaveCount(1, { timeout: 30_000 });
    await expect(answers.first()).not.toContainText('Error:');
    if (!REAL_LLM) {
      await expect(answers.first()).toContainText('Math Tutor', { timeout: 30_000 });
      await expect(answers.first()).toContainText('LLM_API_KEY', { timeout: 30_000 });
    }
    await page.getByTestId('chat-input').fill('and what is a composite number?');
    await expect(page.getByTestId('chat-send')).toBeEnabled({ timeout: 30_000 });
    expect(await page.evaluate(() => Object.keys(sessionStorage)
      .filter((k) => k.startsWith('atmosphere.js:run-id:')).length),
      'the completed run leaves no run id behind').toBe(0);
  });
});
