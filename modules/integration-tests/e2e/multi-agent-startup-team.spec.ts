import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-multi-agent-startup-team']);
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

/** Send a JSON-RPC 2.0 A2A request. */
async function a2aRequest(
  baseUrl: string,
  endpoint: string,
  method: string,
  params: Record<string, unknown> = {},
): Promise<Record<string, unknown>> {
  const res = await fetch(`${baseUrl}${endpoint}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
  });
  return (await res.json()) as Record<string, unknown>;
}

test.describe('Multi-Agent Startup Team', () => {
  // ── Agent registration ──

  test('CEO agent registered at /atmosphere/agent/ceo', async () => {
    const res = await fetch(`${server.baseUrl}/atmosphere/agent/ceo`);
    expect(res.status).not.toBe(404);
  });

  test('server logs confirm CEO agent with protocols', () => {
    const output = server.getOutput();
    expect(output).toContain('/atmosphere/agent/ceo');
    expect(output).toContain("Agent 'ceo' registered");
  });

  test('4 headless agents registered', () => {
    const output = server.getOutput();
    expect(output).toContain("Agent 'research-agent' registered");
    expect(output).toContain("Agent 'strategy-agent' registered");
    expect(output).toContain("Agent 'finance-agent' registered");
    expect(output).toContain("Agent 'writer-agent' registered");
    // All should be headless
    const headlessCount = (output.match(/headless, skills: 1/g) || []).length;
    expect(headlessCount).toBeGreaterThanOrEqual(4);
  });

  // ── A2A Agent Card discovery ──

  test('research agent discoverable via Agent Card', async () => {
    const body = await a2aRequest(server.baseUrl, '/atmosphere/a2a/research',
      'agent/authenticatedExtendedCard');
    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
    expect(result.name).toBe('research-agent');
    const skills = result.skills as { id: string }[];
    expect(skills.length).toBeGreaterThan(0);
    expect(skills[0].id).toBe('web_search');
  });

  test('finance agent discoverable via Agent Card', async () => {
    const body = await a2aRequest(server.baseUrl, '/atmosphere/a2a/finance',
      'agent/authenticatedExtendedCard');
    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
    expect(result.name).toBe('finance-agent');
  });

  // ── A2A task delegation ──

  test('research agent executes web_search skill via A2A', async () => {
    const body = await a2aRequest(server.baseUrl, '/atmosphere/a2a/research',
      'message/send', {
        message: { role: 'user', parts: [{ type: 'text', text: 'test' }],
          metadata: { skillId: 'web_search' } },
        arguments: { query: 'AI developer tools', num_results: '2' },
      });
    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
    const status = result.status as { state: string };
    expect(status.state).toBe('COMPLETED');
    const artifacts = result.artifacts as { parts: { text: string }[] }[];
    expect(artifacts.length).toBeGreaterThan(0);
    expect(artifacts[0].parts[0].text).toContain('search results');
  });

  test('strategy agent executes analyze_strategy skill via A2A', async () => {
    const body = await a2aRequest(server.baseUrl, '/atmosphere/a2a/strategy',
      'message/send', {
        message: { role: 'user', parts: [{ type: 'text', text: 'test' }],
          metadata: { skillId: 'analyze_strategy' } },
        arguments: { market: 'AI tools', research_findings: 'Growing market',
          focus_area: 'entry' },
      });
    const result = body.result as Record<string, unknown>;
    const status = result.status as { state: string };
    expect(status.state).toBe('COMPLETED');
  });

  // ── Streaming response ──

  test('CEO streams a response to user message', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/ceo', 'hello', 20_000);
    expect(result.texts.length).toBeGreaterThan(0);
    // In demo mode: mentions GEMINI_API_KEY. With key: real LLM response.
    // Either way, we should get non-empty text back.
    expect(result.fullText.length).toBeGreaterThan(10);
  });

  // ── Console UI ──

  test('console page loads', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('chat-layout')).toBeVisible();
  });
});
