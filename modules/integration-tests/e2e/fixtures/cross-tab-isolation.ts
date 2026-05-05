import { type Browser, expect } from '@playwright/test';
import { type SampleServer } from './sample-server';

/**
 * Two-tab cross-talk isolation assertion for {@code @AiEndpoint}/{@code @Agent}
 * samples. Pins the regression class fixed in commit 1fbb0958f0 ("fix(ai):
 * isolate @AiEndpoint prompts to the originating client (cross-tab leak)").
 *
 * <p>Pre-fix, {@code AiEndpointHandler.onRequest} broadcast each prompt to
 * every subscribed resource on the per-path broadcaster — N tabs ⇒ N LLM
 * invocations and tab B receiving tab A's response. Post-fix,
 * {@code SUSPENDED_ATMOSPHERE_RESOURCE_UUID} (WS) and
 * {@code X-Atmosphere-tracking-id} (SSE/LP) route the prompt to the
 * originating resource only. This helper reproduces the reproducer steps
 * from the original chrome-devtools investigation against any sample whose
 * Console UI talks to a private (per-resource) endpoint.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Open two isolated browser contexts (independent WS handshakes ⇒
 *       distinct AtmosphereResource UUIDs on the server).</li>
 *   <li>Both navigate to the Console at {@code /atmosphere/console/} and
 *       wait until the WS connection reports "Connected".</li>
 *   <li>Tab A sends a uniquely-tagged prompt (random suffix so no other
 *       state in the page can satisfy the assertion).</li>
 *   <li>Wait for tab A to render the tag (proves the request landed and a
 *       round-trip happened).</li>
 *   <li>Poll tab B's body for {@code observationMs} milliseconds. The tag
 *       must NEVER appear — its presence is the regression.</li>
 * </ol>
 */
export interface CrossTabIsolationOptions {
  /** Defaults to {@code /atmosphere/console/}. */
  consolePath?: string;
  /** {@code data-testid} of the prompt input. Defaults to {@code chat-input}. */
  inputTestId?: string;
  /** {@code data-testid} of the send button. Defaults to {@code chat-send}. */
  sendTestId?: string;
  /** Override the input locator entirely (CSS selector). Wins over inputTestId. */
  inputSelector?: string;
  /** Override the send button locator entirely (CSS selector). Wins over sendTestId. */
  sendSelector?: string;
  /**
   * Window during which tab B is observed for the leaked tag. Defaults to
   * 8000ms — long enough that a streamed response started in tab A would
   * have produced visible bytes if it leaked.
   */
  observationMs?: number;
  /**
   * Skip the "tab A renders the tag" sanity check. Useful for samples where
   * the demo response producer paraphrases instead of echoing — the
   * isolation assertion (tab B silent) still runs.
   */
  skipTabAEchoAssertion?: boolean;
  /** Optional Connected-text matcher. Defaults to {@code /connected/i}. */
  connectedMatcher?: RegExp;
}

export async function assertCrossTabIsolation(
  browser: Browser,
  server: SampleServer,
  opts: CrossTabIsolationOptions = {},
): Promise<void> {
  const consolePath = opts.consolePath ?? '/atmosphere/console/';
  const observationMs = opts.observationMs ?? 8_000;
  const connectedMatcher = opts.connectedMatcher ?? /connected/i;
  // Random tag the page has no other reason to ever render. Including the
  // sample name in the failure message disambiguates which leg of the
  // matrix tripped when the regression file is shared by all samples.
  const tag = `XTAB-${server.config.name}-${Math.random().toString(36).slice(2, 10)}`.toUpperCase();

  const ctxA = await browser.newContext();
  const ctxB = await browser.newContext();
  try {
    const tabA = await ctxA.newPage();
    const tabB = await ctxB.newPage();

    await tabA.goto(server.baseUrl + consolePath);
    await tabB.goto(server.baseUrl + consolePath);

    await expect(tabA.getByText(connectedMatcher)).toBeVisible({ timeout: 20_000 });
    await expect(tabB.getByText(connectedMatcher)).toBeVisible({ timeout: 20_000 });

    // Settle: give the second WS connection a moment to register on the
    // broadcaster before A sends anything. Without this, the race between
    // B's onReady and A's prompt POST can mask the regression on the
    // happy path (B isn't on the broadcaster yet, so the broken broadcast
    // misses it). 500ms is the same window other multi-tab specs use.
    await tabA.waitForTimeout(500);

    const inputA = opts.inputSelector
      ? tabA.locator(opts.inputSelector).first()
      : tabA.getByTestId(opts.inputTestId ?? 'chat-input');
    await inputA.fill(tag);

    if (opts.sendSelector) {
      await tabA.locator(opts.sendSelector).first().click();
    } else {
      await tabA.getByTestId(opts.sendTestId ?? 'chat-send').click();
    }

    if (!opts.skipTabAEchoAssertion) {
      // Tab A must render its own prompt — proves the round-trip
      // actually happened. If this fails, the test is misconfigured
      // (wrong Console path, wrong input selector) and the isolation
      // assertion below is meaningless.
      await expect(tabA.locator('body')).toContainText(tag, { timeout: 15_000 });
    }

    // Observation window: poll tab B's text. The pre-fix bug class
    // manifested in two ways — (a) tab B's @Prompt fires and produces a
    // response; (b) the prompt String itself is broadcast back through
    // tab B's stream. Both surfaces would render the tag in tab B's DOM,
    // so a single substring check covers both.
    const start = Date.now();
    while (Date.now() - start < observationMs) {
      const tabBContent = await tabB.locator('body').innerText();
      expect(
        tabBContent.includes(tag),
        `Cross-tab leak in ${server.config.name}: tab B (no input sent) ` +
        `received tag "${tag}" from tab A at +${Date.now() - start}ms. ` +
        `This is the regression class fixed in commit 1fbb0958f0 — ` +
        `AiEndpointHandler must dispatch prompts to the originating ` +
        `resource only, never broadcast them across the per-path broadcaster.`,
      ).toBe(false);
      await tabB.waitForTimeout(500);
    }
  } finally {
    await ctxA.close();
    await ctxB.close();
  }
}
