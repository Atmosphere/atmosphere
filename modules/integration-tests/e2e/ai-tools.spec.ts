import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-tools']);
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

test.describe('@AiTool Pipeline', () => {
  test('page loads with AI chat layout', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByTestId('chat-send')).toBeVisible();
  });

  test('time query receives a response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What time is it in Tokyo?');
    await page.getByTestId('chat-send').click();

    await expect(page.locator('.message--assistant').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('weather query receives a response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is the weather in Paris?');
    await page.getByTestId('chat-send').click();

    await expect(page.locator('.message--assistant').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('temperature conversion query receives a response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Convert 100F to Celsius');
    await page.getByTestId('chat-send').click();

    await expect(page.locator('.message--assistant').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('greeting receives a response', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello!');
    await page.getByTestId('chat-send').click();

    await expect(page.locator('.message--assistant').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  test('multi-turn conversation works within same session', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    // First message
    await page.getByTestId('chat-input').fill('What time is it in London?');
    await page.getByTestId('chat-send').click();
    await expect(page.locator('.message--assistant').last())
      .not.toBeEmpty({ timeout: 30_000 });

    // Wait for first response to complete
    await page.waitForTimeout(3000);

    // Second message in same session
    await page.getByTestId('chat-input').fill('What is the weather in Sydney?');
    await page.getByTestId('chat-send').click();
    await expect(page.locator('.message--assistant').last())
      .not.toBeEmpty({ timeout: 30_000 });
  });

  // Built-in demo runtime echoes responses but doesn't dispatch tool call events to console
  test.skip('tool activity panel shows tool events after query', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is the weather in Tokyo?');
    await page.getByTestId('chat-send').click();

    // The tool-activity section should appear with tool cards
    await expect(page.getByTestId('tool-activity'))
      .toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId('tool-card').first())
      .toBeVisible({ timeout: 30_000 });
  });

  // ── @RequiresApproval / Human-in-the-Loop ──

  test('reset city data triggers approval-required event in WebSocket frames', async () => {
    const frames = await sendAndCollectFrames(server.baseUrl,
      '/atmosphere/ai-chat', 'Please reset city data for London', 15_000);

    // Demo mode emits approval-required event for reset_city_data
    const approvalEvent = frames.frames.find((f: any) => f.event === 'approval-required');
    expect(approvalEvent).toBeDefined();
    expect(approvalEvent.data.toolName).toBe('reset_city_data');
    expect(approvalEvent.data.message).toContain('reset all cached data');
    expect(approvalEvent.data.approvalId).toBeDefined();
  });


  test('reset_city_data tool registered with approval metadata', () => {
    const output = server.getOutput();
    expect(output).toContain('Registered AI tool: reset_city_data');
  });

  // ── Per-session isolation ──

  // ai-tools is a single-user tool demo: its @AiEndpoint calls session.stream()
  // (reply to the requesting session), NOT broadcastReply/a shared room. So a
  // prompt from one client must reach ONLY that client's session — the others,
  // which never asked, stay empty. (This test previously claimed "all clients
  // receive broadcast responses" and passed only because a broad
  // [class*=message] selector matched a non-assistant element on the silent
  // clients; the precise .message--assistant selector exposes the real,
  // correct behaviour, which is isolation.)
  test('a prompt reaches only the requesting session (per-session isolation)', async ({ browser }) => {
    const ctx1 = await browser.newContext();
    const ctx2 = await browser.newContext();
    const page1 = await ctx1.newPage();
    const page2 = await ctx2.newPage();

    await page1.goto(server.baseUrl + '/atmosphere/console/');
    await page2.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page1.getByTestId('chat-input')).toBeVisible();
    await expect(page2.getByTestId('chat-input')).toBeVisible();

    // Only client 1 sends a prompt.
    await page1.getByTestId('chat-input').fill('What time is it in Tokyo?');
    await page1.getByTestId('chat-send').click();

    // The sender gets the streamed assistant reply...
    await expect(page1.locator('.message--assistant').last())
      .not.toBeEmpty({ timeout: 30_000 });

    // ...and client 2, which never asked, never receives it. Assert after the
    // sender's reply has landed so any (incorrect) broadcast window has elapsed.
    await expect(page2.locator('.message--assistant')).toHaveCount(0);

    await ctx1.close();
    await ctx2.close();
  });
});
