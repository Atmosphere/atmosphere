import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { fetchWebTransportInfo, waitForConsoleMessage } from './helpers/webtransport-helper';

/**
 * WebTransport E2E tests — verifies that agent, multiagent, and chat
 * work correctly over WebTransport/HTTP3 (not just WebSocket).
 *
 * These tests use the sample frontends which auto-discover WebTransport
 * via /api/webtransport-info and connect with fallback to WebSocket.
 * We verify the transport used via console log messages.
 */

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
    let info = await fetchWebTransportInfo(server.baseUrl);
    for (let i = 0; i < 10 && info && !info.enabled; i++) {
      await new Promise(r => setTimeout(r, 1000));
      info = await fetchWebTransportInfo(server.baseUrl);
    }
    expect(info).not.toBeNull();
    expect(info!.enabled).toBe(true);
    expect(info!.port).toBe(4443);
    expect(info!.certificateHash).toBeDefined();
  });

  test('@smoke chat connects via webtransport', async ({ page }) => {
    const wtConsole = waitForConsoleMessage(page, /via webtransport/i);
    await page.goto(server.baseUrl);
    const msg = await wtConsole;
    expect(msg).toContain('webtransport');
  });

  test('chat message round-trip over WebTransport', async ({ page }) => {
    const wtConsole = waitForConsoleMessage(page, /WebTransport connection established/i);
    await page.goto(server.baseUrl);
    await wtConsole;

    // Join as a user
    const input = page.locator('input[placeholder*="name" i], input[placeholder*="join" i]');
    await input.fill('WTUser');
    await input.press('Enter');

    // Wait for join acknowledgment
    await expect(page.getByText(/Joined room/i)).toBeVisible({ timeout: 10_000 });

    // Send a message
    const msgInput = page.locator('input[placeholder*="message" i]');
    await msgInput.fill('Hello over HTTP/3!');
    await msgInput.press('Enter');

    // Verify message appears (broadcast back to sender)
    await expect(page.getByText('Hello over HTTP/3!')).toBeVisible({ timeout: 10_000 });
  });

  test('no WebTransport errors in console', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error' && msg.text().toLowerCase().includes('webtransport')) {
        errors.push(msg.text());
      }
    });

    const wtConsole = waitForConsoleMessage(page, /WebTransport connection established/i);
    await page.goto(server.baseUrl);
    await wtConsole;

    // Wait a moment for any delayed errors
    await page.waitForTimeout(2000);
    expect(errors).toEqual([]);
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
    let info = await fetchWebTransportInfo(server.baseUrl);
    for (let i = 0; i < 10 && info && !info.enabled; i++) {
      await new Promise(r => setTimeout(r, 1000));
      info = await fetchWebTransportInfo(server.baseUrl);
    }
    expect(info).not.toBeNull();
    expect(info!.enabled).toBe(true);
    expect(info!.port).toBe(4445);
  });

  test('@smoke AI tools connects via webtransport', async ({ page }) => {
    const wtConsole = waitForConsoleMessage(page, /via webtransport/i);
    await page.goto(server.baseUrl);
    const msg = await wtConsole;
    expect(msg).toContain('webtransport');
  });

  test('AI tool call streams response over WebTransport', async ({ page }) => {
    const wtConsole = waitForConsoleMessage(page, /WebTransport connection established/i);
    await page.goto(server.baseUrl);
    await wtConsole;

    // Find input and send a time query (triggers @AiTool)
    const input = page.locator('input[type="text"], textarea').first();
    await input.fill('What time is it?');
    await input.press('Enter');

    // Wait for AI response containing time
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('no WebTransport errors in console', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error' && msg.text().toLowerCase().includes('webtransport')) {
        errors.push(msg.text());
      }
    });

    const wtConsole = waitForConsoleMessage(page, /WebTransport connection established/i);
    await page.goto(server.baseUrl);
    await wtConsole;

    await page.waitForTimeout(2000);
    expect(errors).toEqual([]);
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
    // SmartLifecycle starts the HTTP/3 sidecar late — retry until enabled
    let info = await fetchWebTransportInfo(server.baseUrl);
    for (let i = 0; i < 10 && info && !info.enabled; i++) {
      await new Promise(r => setTimeout(r, 1000));
      info = await fetchWebTransportInfo(server.baseUrl);
    }
    expect(info).not.toBeNull();
    expect(info!.enabled).toBe(true);
    expect(info!.port).toBe(4446);
  });

  test('@smoke multi-agent connects via webtransport', async ({ page }) => {
    const wtConsole = waitForConsoleMessage(page, /via webtransport/i);
    await page.goto(server.baseUrl);
    const msg = await wtConsole;
    expect(msg).toContain('webtransport');
  });

  test('coordinator streams response over WebTransport', async ({ page }) => {
    const wtConsole = waitForConsoleMessage(page, /WebTransport connection established/i);
    await page.goto(server.baseUrl);
    await wtConsole;

    // Send a prompt to the CEO coordinator
    const input = page.locator('input[type="text"], textarea').first();
    await input.fill('What are the top trends in AI?');
    await input.press('Enter');

    // Wait for agent collaboration cards or CEO synthesis to appear
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

  test('no WebTransport errors in console', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error' && msg.text().toLowerCase().includes('webtransport')) {
        errors.push(msg.text());
      }
    });

    const wtConsole = waitForConsoleMessage(page, /WebTransport connection established/i);
    await page.goto(server.baseUrl);
    await wtConsole;

    await page.waitForTimeout(2000);
    expect(errors).toEqual([]);
  });
});
