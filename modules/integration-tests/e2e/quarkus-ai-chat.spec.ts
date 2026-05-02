import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Admission test for `samples/quarkus-ai-chat` and the underlying
 * `atmosphere-quarkus-langchain4j` bridge module.
 *
 * The sample boots Quarkus 3.31 + Quarkus LangChain4j; the bridge looks up the
 * CDI synthetic `StreamingChatModel` produced by Quarkus L4j and installs it
 * on `LangChain4jAgentRuntime` at startup. This spec verifies:
 *
 *   1. The sample starts (Quarkus boot + Atmosphere servlet init).
 *   2. `/api/admin/runtimes/active` reports `langchain4j` — proof that the
 *      AgentRuntime SPI resolved the bridge-installed runtime, not the demo
 *      fallback that fires when no chat model is registered.
 *   3. The chat UI loads and atmosphere.js negotiates a transport to the
 *      `@AiEndpoint` over WebSocket — i.e. the Quarkus extension scanned
 *      the `@AiEndpoint` annotation and registered the AI servlet path.
 *
 * Real LLM round-trips are NOT asserted here — the sample defaults to
 * `LLM_API_KEY=dummy-not-real`, which is enough for Quarkus L4j to
 * materialise the `StreamingChatModel` bean (so the bridge has something
 * to wire) but a real `chat/completions` call would 401 at the upstream.
 * Live round-trips are validated locally via chrome-devtools.
 */

let server: SampleServer;

// startSample's port + http + websocket probes add up to ~135s on a slow
// runner; bump the hook timeout above Playwright's 90s default so a CI
// cold start does not get truncated mid-await.
test.beforeAll(async ({}, testInfo) => {
  testInfo.setTimeout(180_000);
  server = await startSample(SAMPLES['quarkus-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Quarkus AI Chat', () => {
  test('runtimes/active reports langchain4j (bridge wired the StreamingChatModel)',
    async ({ request }) => {
      const res = await request.get(
        `${server.baseUrl}/api/admin/runtimes/active`,
      );
      expect(res.ok()).toBeTruthy();

      const active = await res.json();
      // If the bridge had failed, AgentRuntimeResolver would have fallen
      // back to the built-in demo runtime here; asserting "langchain4j"
      // pins the bridge to the critical path.
      expect(active.name).toBe('langchain4j');
      expect(active.isAvailable).toBe(true);
      expect(Array.isArray(active.capabilities)).toBeTruthy();
      expect(active.capabilities).toContain('TEXT_STREAMING');
    });

  test('chat UI loads and reaches Connected state', async ({ page }) => {
    await page.goto(server.baseUrl + '/');

    // The status div flips to "Connected" once atmosphere.js has finished
    // negotiating the WebSocket handshake against /atmosphere/ai-chat,
    // proving the @AiEndpoint scan in atmosphere-quarkus-extension picked
    // up the AiChat handler and registered the servlet path.
    const status = page.locator('#status');
    await expect(status).toHaveText(/Connected/i, { timeout: 30_000 });
    await expect(status).toHaveAttribute('data-state', 'Connected');

    // The Send button is initially disabled (status !== Connected); after
    // the handshake it must be enabled. Visible-and-enabled is enough to
    // confirm the page is interactive.
    await expect(page.getByRole('button', { name: /send/i })).toBeEnabled();
  });
});
