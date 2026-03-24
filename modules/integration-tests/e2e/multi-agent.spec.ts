import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-multi-agent']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Send a message via WebSocket and collect the streamed response. */
function sendAndCollect(
  baseUrl: string,
  path: string,
  message: string,
  timeoutMs = 30_000,
): Promise<{ texts: string[]; fullText: string; closed: boolean }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http://', 'ws://') + path;
    const ws = new WebSocket(wsUrl);
    const texts: string[] = [];
    let opened = false;
    const timer = setTimeout(() => {
      ws.close();
      resolve({ texts, fullText: texts.join(''), closed: true });
    }, timeoutMs);

    ws.on('open', () => {
      opened = true;
      // Atmosphere protocol: first message is a handshake/subscribe, then we send
      // For @Agent endpoints, just send the text directly
      ws.send(message);
    });

    ws.on('message', (data) => {
      const raw = data.toString();
      // Atmosphere TrackMessageSizeInterceptor wraps messages with length prefix
      // Format: <length>|<message>
      const parts = raw.split('|');
      for (const part of parts) {
        const trimmed = part.trim();
        if (trimmed && !trimmed.match(/^\d+$/) && trimmed !== 'X') {
          texts.push(trimmed);
        }
      }
    });

    ws.on('close', () => {
      clearTimeout(timer);
      resolve({ texts, fullText: texts.join(''), closed: true });
    });

    ws.on('error', (err) => {
      clearTimeout(timer);
      if (!opened) {
        reject(new Error(`WebSocket connection failed: ${err.message}`));
      } else {
        resolve({ texts, fullText: texts.join(''), closed: true });
      }
    });
  });
}

test.describe('Multi-Agent Startup Team', () => {
  test('agent endpoint is registered', async () => {
    const res = await fetch(`${server.baseUrl}/atmosphere/agent/startup-ceo`);
    expect(res.status).not.toBe(404);
  });

  test('MCP endpoint auto-registered alongside agent', () => {
    const output = server.getOutput();
    expect(output).toContain('/atmosphere/agent/startup-ceo/mcp');
  });

  test('server logs confirm 4 tools registered', () => {
    const output = server.getOutput();
    expect(output).toContain('web_search');
    expect(output).toContain('analyze_strategy');
    expect(output).toContain('financial_model');
    expect(output).toContain('write_report');
    expect(output).toContain('tools: 4');
  });

  test('agent registered at correct path', () => {
    const output = server.getOutput();
    expect(output).toContain('/atmosphere/agent/startup-ceo');
    expect(output).toContain("Agent 'startup-ceo' registered");
  });

  test('agent has conversation memory enabled', () => {
    const output = server.getOutput();
    expect(output).toContain('memory: on');
  });

  test('demo mode streams AI tools market response', async () => {
    const result = await sendAndCollect(
      server.baseUrl,
      '/atmosphere/agent/startup-ceo',
      'Analyze AI developer tools',
      20_000,
    );

    expect(result.texts.length).toBeGreaterThan(0);
    expect(result.fullText).toContain('GEMINI_API_KEY');
  });

  test('demo mode includes all agent sections for AI query', async () => {
    const result = await sendAndCollect(
      server.baseUrl,
      '/atmosphere/agent/startup-ceo',
      'AI developer tools market',
      20_000,
    );

    expect(result.fullText).toContain('Research Agent');
    expect(result.fullText).toContain('Strategy Agent');
    expect(result.fullText).toContain('Finance Agent');
  });

  test('default demo response describes the team', async () => {
    const result = await sendAndCollect(
      server.baseUrl,
      '/atmosphere/agent/startup-ceo',
      'hello',
      20_000,
    );

    expect(result.fullText).toContain('Research Agent');
    expect(result.fullText).toContain('Strategy Agent');
    expect(result.fullText).toContain('Finance Agent');
    expect(result.fullText).toContain('Writer Agent');
  });

  test('console page loads with chat UI', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
  });
});
