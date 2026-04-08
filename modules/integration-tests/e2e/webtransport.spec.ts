import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { fetchWebTransportInfo, waitForConsoleMessage } from './helpers/webtransport-helper';

/**
 * WebTransport E2E tests — verifies the WebTransport discovery endpoint,
 * transport negotiation, and app functionality across samples.
 *
 * Browser tests verify the app works with automatic transport negotiation
 * (WebTransport when available, WebSocket fallback otherwise). The
 * /api/webtransport-info endpoint is tested via HTTP (no browser needed).
 *
 * Raw HTTP/3 protocol tests are in webtransport-raw.spec.ts.
 */

/** Retry fetchWebTransportInfo until enabled (SmartLifecycle starts HTTP/3 late). */
async function waitForWebTransportReady(
  baseUrl: string,
  maxRetries = 15,
): Promise<{ port: number; enabled: boolean; certificateHash?: string } | null> {
  for (let i = 0; i < maxRetries; i++) {
    const info = await fetchWebTransportInfo(baseUrl);
    if (info?.enabled) return info;
    await new Promise(r => setTimeout(r, 1000));
  }
  return fetchWebTransportInfo(baseUrl);
}

/** Wait for Atmosphere to connect via any transport (webtransport or websocket). */
function waitForConnection(page: import('@playwright/test').Page, timeoutMs = 20_000) {
  return waitForConsoleMessage(page, /connection established/i, timeoutMs);
}

// ── Chat over WebTransport ────────────────────────────────────────────

test.describe('Chat over WebTransport', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    server = await startSample(SAMPLES['spring-boot-chat']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('WebTransport info endpoint returns port and cert hash', async () => {
    const info = await waitForWebTransportReady(server.baseUrl);
    expect(info).not.toBeNull();
    expect(info!.enabled).toBe(true);
    expect(info!.port).toBe(4443);
    expect(info!.certificateHash).toBeDefined();
  });

  test('@smoke chat connects and negotiates transport', async ({ page }) => {
    const connected = waitForConnection(page);
    await page.goto(server.baseUrl);
    const msg = await connected;
    // Atmosphere logs "WebTransport connection established" or "WebSocket connection established"
    expect(msg.toLowerCase()).toContain('connection established');
  });

  test('chat message round-trip', async ({ page }) => {
    const connected = waitForConnection(page);
    await page.goto(server.baseUrl);
    await connected;

    const input = page.locator('input[placeholder*="name" i], input[placeholder*="join" i]');
    await input.fill('WTUser');
    await input.press('Enter');

    await expect(page.getByText(/Joined room/i)).toBeVisible({ timeout: 10_000 });

    const msgInput = page.locator('input[placeholder*="message" i]');
    await msgInput.fill('Hello over HTTP/3!');
    await msgInput.press('Enter');

    await expect(page.getByText('Hello over HTTP/3!')).toBeVisible({ timeout: 10_000 });
  });

  test('no handshake errors persist after connection', async ({ page }) => {
    const connected = waitForConnection(page);
    await page.goto(server.baseUrl);
    await connected;

    // After connection is established, there should be no visible error banners
    await page.waitForTimeout(2000);
    const errorBanners = await page.locator('text=/Opening handshake failed/').count();
    expect(errorBanners).toBe(0);
  });
});

// ── AI Tools over WebTransport ────────────────────────────────────────

test.describe('AI Tools over WebTransport', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-ai-tools']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('WebTransport info endpoint returns port 4445', async () => {
    const info = await waitForWebTransportReady(server.baseUrl);
    expect(info).not.toBeNull();
    expect(info!.enabled).toBe(true);
    expect(info!.port).toBe(4445);
  });

  test('@smoke AI tools connects and negotiates transport', async ({ page }) => {
    const connected = waitForConnection(page);
    await page.goto(server.baseUrl);
    const msg = await connected;
    expect(msg.toLowerCase()).toContain('connection established');
  });

  test('AI tool call streams response', async ({ page }) => {
    const connected = waitForConnection(page);
    await page.goto(server.baseUrl);
    await connected;

    const input = page.locator('input[type="text"], textarea').first();
    await input.fill('What time is it?');
    await input.press('Enter');

    // Wait for any response to appear in the chat
    await expect(page.locator('body')).toContainText(/\d{1,2}:\d{2}|time|AM|PM/i, { timeout: 30_000 });
  });

  test('no handshake errors persist after connection', async ({ page }) => {
    const connected = waitForConnection(page);
    await page.goto(server.baseUrl);
    await connected;

    await page.waitForTimeout(2000);
    const errorBanners = await page.locator('text=/Opening handshake failed/').count();
    expect(errorBanners).toBe(0);
  });
});

// ── Multi-Agent over WebTransport ─────────────────────────────────────

test.describe('Multi-Agent Startup Team over WebTransport', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-multi-agent-startup-team']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('WebTransport info endpoint returns port 4446', async () => {
    const info = await waitForWebTransportReady(server.baseUrl);
    expect(info).not.toBeNull();
    expect(info!.enabled).toBe(true);
    expect(info!.port).toBe(4446);
  });

  test('@smoke multi-agent connects and negotiates transport', async ({ page }) => {
    const connected = waitForConnection(page);
    await page.goto(server.baseUrl);
    const msg = await connected;
    expect(msg.toLowerCase()).toContain('connection established');
  });

  test('coordinator streams response', async ({ page }) => {
    const connected = waitForConnection(page);
    await page.goto(server.baseUrl);
    await connected;

    const input = page.locator('input[type="text"], textarea').first();
    await input.fill('What are the top trends in AI?');
    await input.press('Enter');

    // Wait for agent collaboration or CEO synthesis
    await expect(page.getByText(/Research Agent|CEO|Welcome to the A2A/i).first())
      .toBeVisible({ timeout: 30_000 });
  });

  test('4 headless agents registered', () => {
    const output = server.getOutput();
    expect(output).toContain("Agent 'research-agent' registered");
    expect(output).toContain("Agent 'strategy-agent' registered");
    expect(output).toContain("Agent 'finance-agent' registered");
    expect(output).toContain("Agent 'writer-agent' registered");
  });

  test('no handshake errors persist after connection', async ({ page }) => {
    const connected = waitForConnection(page);
    await page.goto(server.baseUrl);
    await connected;

    await page.waitForTimeout(2000);
    const errorBanners = await page.locator('text=/Opening handshake failed/').count();
    expect(errorBanners).toBe(0);
  });
});
