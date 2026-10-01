import { test, expect, type Page } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { advertiseUnreachableWebTransport, expectAnswer } from './helpers/transport-fallback';

/**
 * WebTransport → WebSocket fallback, through the bundled Console.
 *
 * When /api/console/info advertises a WebTransport sidecar, the Console
 * subscribes over WebTransport first with WebSocket as the fallback. Here the
 * advertised sidecar is unreachable (helpers/transport-fallback.ts), so the
 * fallback is forced, not hoped for:
 *  - with the WebTransport API present (Chromium), the handshake fails and the
 *    Console must land on WebSocket, flagged as a fallback;
 *  - without the API (Firefox / WebKit, simulated on Chromium by removing
 *    `window.WebTransport`), it must not throw and must land on WebSocket too.
 * Either way the chat is answered — an assistant reply, never only the user's
 * own bubble, which the Console renders before anything is sent.
 *
 * The /api/webtransport-info discovery contract (port, 44-char certificate
 * hash) is asserted by webtransport.spec.ts against a sample that runs the
 * HTTP/3 sidecar; dentist-agent runs none, so a check of it here could only
 * ever skip.
 *
 * <p>On Chromium this file runs under its own {@code webtransport-fallback}
 * project (per-push e2e.yml leg). It is also named in the crossBrowserSpecs
 * regex in playwright.config.ts, so the opt-in Firefox + WebKit projects pick
 * it up when {@code E2E_ALL_BROWSERS=true}.</p>
 */
test.describe('WebTransport / WebSocket fallback', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    // dentist-agent: no auth, @Agent accepts raw text, slash commands answer
    // keyless. Same fixture as transport-fallback.spec.ts.
    server = await startSample(SAMPLES['spring-boot-dentist-agent']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  async function expectWebSocketFallback(page: Page) {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    const badge = page.getByTestId('atmosphere-connection-status');
    await expect(badge).toHaveAttribute('data-phase', 'open', { timeout: 20_000 });
    await expect(badge).toHaveAttribute('data-transport', 'websocket');
    await expect(badge).toHaveAttribute('data-via-fallback', 'true');
  }

  test('an unreachable WebTransport sidecar falls back to WebSocket, and the chat is answered',
        async ({ page }) => {
    await advertiseUnreachableWebTransport(page);
    await expectWebSocketFallback(page);

    await expectAnswer(page, '/help', 'Available commands', 1);
    await expectAnswer(page, '/pain', 'Pain Management', 2);
  });

  test('a browser without the WebTransport API falls back to WebSocket, and the chat is answered',
        async ({ page }) => {
    await page.addInitScript(() => {
      delete (window as { WebTransport?: unknown }).WebTransport;
    });
    await advertiseUnreachableWebTransport(page);
    await expectWebSocketFallback(page);

    await expectAnswer(page, '/help', 'Available commands', 1);
  });
});
