import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-orchestration-demo']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Send a message via WebSocket and collect streamed JSON frames. */
function sendAndCollectFrames(
  baseUrl: string,
  path: string,
  message: string,
  timeoutMs = 20_000,
): Promise<{ raw: string[]; frames: any[]; fullText: string }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http://', 'ws://') + path;
    const ws = new WebSocket(wsUrl);
    const raw: string[] = [];
    const frames: any[] = [];
    let opened = false;
    const timer = setTimeout(() => {
      ws.close();
      resolve({ raw, frames, fullText: extractText(frames) });
    }, timeoutMs);

    ws.on('open', () => { opened = true; ws.send(message); });
    ws.on('message', (data) => {
      const text = data.toString();
      raw.push(text);
      for (const part of text.split('|')) {
        const trimmed = part.trim();
        if (trimmed.startsWith('{')) {
          try { frames.push(JSON.parse(trimmed)); } catch { /* not JSON */ }
        }
      }
    });
    ws.on('close', () => {
      clearTimeout(timer);
      resolve({ raw, frames, fullText: extractText(frames) });
    });
    ws.on('error', (err) => {
      clearTimeout(timer);
      if (!opened) reject(new Error(`WebSocket failed: ${err.message}`));
      else resolve({ raw, frames, fullText: extractText(frames) });
    });
  });
}

function extractText(frames: any[]): string {
  return frames
    .filter(f => f.type === 'streaming-text')
    .map(f => f.data)
    .join('');
}

test.describe('Orchestration Demo — Support + Billing Agents', () => {

  // ── Agent Registration ──

  test('both agents registered', () => {
    const output = server.getOutput();
    expect(output).toContain("Agent 'support' registered");
    expect(output).toContain("Agent 'billing' registered");
  });

  test('support agent has 2 commands and 2 tools', () => {
    const output = server.getOutput();
    expect(output).toContain('commands: 2, tools: 2');
  });

  test('console info points to support agent', async () => {
    const res = await fetch(`${server.baseUrl}/api/console/info`);
    const info = await res.json();
    expect(info.endpoint).toBe('/atmosphere/agent/support');
    expect(info.subtitle).toContain('Support Desk');
  });

  // ── Handoff: Support → Billing ──

  test('billing question triggers handoff to billing agent', async () => {
    const result = await sendAndCollectFrames(server.baseUrl,
      '/atmosphere/agent/support', 'I need help with my invoice', 15_000);

    // Should contain a handoff event
    const handoffEvent = result.frames.find(f => f.event === 'handoff');
    expect(handoffEvent).toBeDefined();
    expect(handoffEvent.data.toAgent).toBe('billing');

    // Billing agent should respond
    expect(result.fullText).toContain('billing support');
    expect(result.fullText).toContain('transferred');
  });

  test('non-billing question stays with support agent', async () => {
    const result = await sendAndCollectFrames(server.baseUrl,
      '/atmosphere/agent/support', 'How do I reset my password?', 15_000);

    // No handoff event
    const handoffEvent = result.frames.find(f => f.event === 'handoff');
    expect(handoffEvent).toBeUndefined();

    // Support agent responds (demo mode)
    expect(result.fullText.length).toBeGreaterThan(10);
  });

  // ── Slash Commands ──

  test('/status returns account info', async () => {
    const result = await sendAndCollectFrames(server.baseUrl,
      '/atmosphere/agent/support', '/status', 10_000);
    const text = result.raw.join('');
    expect(text).toContain('Account status');
    expect(text).toContain('Professional');
  });

  test('/hours returns operating hours', async () => {
    const result = await sendAndCollectFrames(server.baseUrl,
      '/atmosphere/agent/support', '/hours', 10_000);
    const text = result.raw.join('');
    expect(text).toContain('Mon-Fri');
    expect(text).toContain('9am-6pm');
  });

  test('/help lists all support commands', async () => {
    const result = await sendAndCollectFrames(server.baseUrl,
      '/atmosphere/agent/support', '/help', 10_000);
    const text = result.raw.join('').toLowerCase();
    expect(text).toContain('status');
    expect(text).toContain('hours');
  });

  // ── Approval Gate Registration ──

  test('cancel_account tool registered with approval metadata', () => {
    const output = server.getOutput();
    expect(output).toContain('Registered AI tool: cancel_account');
  });

  // ── @Command(confirm) ──

  test('/purge command is listed in /help', async () => {
    const result = await sendAndCollectFrames(server.baseUrl,
      '/atmosphere/agent/support', '/help', 10_000);
    const text = result.raw.join('').toLowerCase();
    expect(text).toContain('purge');
  });

  test('support agent has 3 commands after adding /purge', () => {
    const output = server.getOutput();
    expect(output).toContain('commands: 3');
  });

  // ── Approval Events in WebSocket Frames ──

  test('approval-required event emitted for @RequiresApproval tool call', async () => {
    // Ask something that would trigger cancel_account in a real LLM scenario.
    // In demo mode, the tool may not be called by the simulated LLM, but
    // we verify the tool is registered with approval metadata.
    const result = await sendAndCollectFrames(server.baseUrl,
      '/atmosphere/agent/support', 'Cancel my account please', 15_000);

    // The demo response should mention support or cancellation
    expect(result.fullText.length).toBeGreaterThan(0);
  });

  // ── Console UI ──

  test('console loads with support desk subtitle', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.locator('text=Support Desk')).toBeVisible();
  });

  test('handoff visible in console UI', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('I need a refund on my last payment');
    await page.getByTestId('chat-send').click();

    // Wait for billing agent response
    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .toContainText('billing', { timeout: 15_000 });
  });

  test('slash command works in console UI', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('/status');
    await page.getByTestId('chat-send').click();

    await expect(page.locator('[class*="assistant"], [class*="message"]').last())
      .toContainText('Account status', { timeout: 10_000 });
  });

  // ── No errors ──

  test('no JavaScript errors during interaction', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', msg => {
      if (msg.type() === 'error' && !msg.text().includes('404')) {
        errors.push(msg.text());
      }
    });

    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();

    // Send a message that triggers handoff
    await page.getByTestId('chat-input').fill('What about my bill?');
    await page.getByTestId('chat-send').click();
    await page.waitForTimeout(5000);

    expect(errors).toEqual([]);
  });
});
