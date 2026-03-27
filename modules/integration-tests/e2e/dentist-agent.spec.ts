import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-dentist-agent']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Send a message via WebSocket and collect the streamed response. */
function sendAndCollect(
  baseUrl: string,
  path: string,
  message: string,
  timeoutMs = 20_000,
): Promise<{ texts: string[]; fullText: string }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http://', 'ws://') + path;
    const ws = new WebSocket(wsUrl);
    const texts: string[] = [];
    let opened = false;
    const timer = setTimeout(() => {
      ws.close();
      resolve({ texts, fullText: texts.join('') });
    }, timeoutMs);

    ws.on('open', () => { opened = true; ws.send(message); });
    ws.on('message', (data) => {
      const raw = data.toString();
      const parts = raw.split('|');
      for (const part of parts) {
        const trimmed = part.trim();
        if (trimmed && !trimmed.match(/^\d+$/) && trimmed !== 'X') {
          texts.push(trimmed);
        }
      }
    });
    ws.on('close', () => { clearTimeout(timer); resolve({ texts, fullText: texts.join('') }); });
    ws.on('error', (err) => {
      clearTimeout(timer);
      if (!opened) reject(new Error(`WebSocket failed: ${err.message}`));
      else resolve({ texts, fullText: texts.join('') });
    });
  });
}

test.describe('Dentist Agent', () => {
  // ── Agent registration ──

  test('agent registered at /atmosphere/agent/dentist', async () => {
    const res = await fetch(`${server.baseUrl}/atmosphere/agent/dentist`);
    expect(res.status).not.toBe(404);
  });

  test('server logs confirm agent with tools and commands', () => {
    const output = server.getOutput();
    expect(output).toContain("Agent 'dentist' registered");
    expect(output).toContain('commands: 3');
    expect(output).toContain('tools: 2');
  });

  test('MCP endpoint registered', () => {
    const output = server.getOutput();
    expect(output).toContain('/atmosphere/agent/dentist/mcp');
  });

  // ── Slash commands ──

  test('/firstaid returns first-aid steps', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', '/firstaid', 10_000);
    expect(result.fullText).toContain('Broken Tooth First Aid');
    expect(result.fullText).toContain('Rinse your mouth');
    expect(result.fullText).toContain('cold compress');
  });

  test('/urgency returns triage guidance', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', '/urgency', 10_000);
    expect(result.fullText).toContain('GO TO ER NOW');
    expect(result.fullText).toContain('SEE DENTIST TODAY');
    expect(result.fullText).toContain('SEE DENTIST WITHIN');
  });

  test('/pain returns pain management tips', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', '/pain', 10_000);
    expect(result.fullText).toContain('Pain Management');
    expect(result.fullText).toContain('ibuprofen');
    expect(result.fullText.toLowerCase()).toContain('cold compress');
  });

  // ── Streaming response (demo mode — no API key) ──

  test('streams a response to a dental question', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', 'I chipped my tooth', 15_000);
    expect(result.texts.length).toBeGreaterThan(0);
    expect(result.fullText.length).toBeGreaterThan(10);
  });

  // ── Console UI ──

  test('console page loads', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
  });

  // ── Channel webhooks registered ──

  test('telegram and slack webhooks registered', () => {
    const output = server.getOutput();
    expect(output).toContain('Registered telegram channel at /webhook/telegram');
    expect(output).toContain('Registered slack channel at /webhook/slack');
  });

  // ── Behavioral depth tests ──

  test('unknown slash command returns help text', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', '/unknown', 10_000);
    // The agent should gracefully handle unknown commands by returning
    // help information or listing available commands
    const text = result.fullText.toLowerCase();
    expect(
      text.includes('help') ||
      text.includes('available') ||
      text.includes('command') ||
      text.includes('firstaid') ||
      text.includes('unknown'),
    ).toBe(true);
    expect(result.fullText.length).toBeGreaterThan(0);
  });

  test('/help lists all available commands', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/dentist', '/help', 10_000);
    const text = result.fullText.toLowerCase();
    // Should list the 3 known commands
    expect(text).toContain('firstaid');
    expect(text).toContain('urgency');
    expect(text).toContain('pain');
  });
});
