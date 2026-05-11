import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { fetchWebTransportInfo } from './helpers/webtransport-helper';

/**
 * WebTransport → WebSocket fallback E2E across Chromium / Firefox / WebKit.
 *
 * The transport selector must:
 *  - Discover WebTransport availability via /api/webtransport-info on every
 *    browser regardless of WT support.
 *  - On Chromium: connect via WebTransport when the server advertises a port.
 *  - On Firefox / WebKit: detect that {@code window.WebTransport} is undefined
 *    and fall back to WebSocket *without* hard-failing the connection.
 *  - On every browser, the chat path keeps working.
 *
 * Closes the gap where {@code webtransport.spec.ts} ran Chromium-only and
 * a transport-selector regression on Firefox/WebKit (e.g. throwing instead
 * of falling back) would have shipped silently.
 *
 * <p>The crossBrowserSpecs regex in playwright.config.ts already matches
 * any spec name containing "fallback" — Firefox + WebKit projects pick this
 * file up automatically when {@code E2E_ALL_BROWSERS=true}.</p>
 */
test.describe('WebTransport / WebSocket fallback', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    // dentist-agent: no auth, @Agent accepts raw text. Same fixture choice
    // as transport-fallback.spec.ts for consistency.
    server = await startSample(SAMPLES['spring-boot-dentist-agent']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('console connects on every browser regardless of WebTransport support',
        async ({ page, browserName }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');

    // The transport selector must converge to "Connected" on every browser.
    // On Chromium it may be WebTransport; on Firefox/WebKit it MUST fall back
    // to WebSocket without throwing.
    await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 20_000 });

    // Sanity check: confirm the WebTransport API surface the client probes
    // matches the browser we're on. This pins the assumption the fallback
    // path is actually being taken on FF / WebKit (not silently skipped).
    const hasWtApi = await page.evaluate(() => typeof (window as { WebTransport?: unknown }).WebTransport !== 'undefined');
    if (browserName === 'chromium') {
      expect(hasWtApi).toBe(true);
    } else {
      expect(hasWtApi).toBe(false);
    }
  });

  test('chat round-trip works on every browser', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 20_000 });

    await page.getByTestId('chat-input').fill('Hello from cross-browser fallback');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('message-list'))
      .toContainText('Hello from cross-browser fallback', { timeout: 15_000 });
  });

  test('WebTransport info endpoint is browser-agnostic', async () => {
    // The discovery endpoint is plain HTTP — same response on every browser.
    // Pinning this here means a regression to the info endpoint surfaces in
    // every cross-browser leg, not just Chromium.
    const info = await fetchWebTransportInfo(server.baseUrl);
    if (info == null) {
      test.skip(true, 'netty-codec-http3 not on classpath in this environment');
      return;
    }
    if (info.enabled) {
      expect(info.port).toBeGreaterThan(0);
      expect(info.certificateHash).toBeDefined();
      // Cert hash must be 44-char base64 (SHA-256) — pinned in the unit
      // tests too (ReactorNettyTransportServerTest), but worth asserting on
      // the wire to catch a mid-stack regression in WebTransportInfoController.
      expect(info.certificateHash!.length).toBe(44);
      expect(info.certificateHash!.endsWith('=')).toBe(true);
    } else {
      // WebTransport is opt-in; if disabled, the endpoint must say so cleanly
      // (Invariant #5 — Runtime Truth).
      expect(info.port).toBe(0);
    }
  });
});
