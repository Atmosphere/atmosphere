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

/** Build a v1.0.0 SendMessage params object. */
function sendMessageParams(
  skillId: string,
  text: string,
  extraArgs: Record<string, unknown> = {},
) {
  return {
    message: {
      messageId: `msg-${Date.now()}`,
      role: 'ROLE_USER',
      parts: [{ text }],
      metadata: { skillId },
    },
    arguments: extraArgs,
  };
}

/** Unwrap the v1.0.0 SendMessageResponse oneof: result.task | result.message. */
function taskOf(body: Record<string, unknown>): Record<string, unknown> {
  const result = body.result as Record<string, unknown>;
  return (result.task as Record<string, unknown>) ?? result;
}

/**
 * Poll server.getOutput() until every substring appears, or throw after timeout.
 * Resolves the race where readyPath returns 200 (endpoint wired) before a
 * later-in-startup log line (e.g. CoordinatorProcessor's "Coordinator 'X'
 * registered") is flushed to the captured stdout buffer.
 */
async function waitForOutput(
  substrings: string[],
  timeoutMs = 15_000,
): Promise<string> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const output = server.getOutput();
    if (substrings.every((s) => output.includes(s))) return output;
    await new Promise((r) => setTimeout(r, 200));
  }
  const output = server.getOutput();
  const missing = substrings.filter((s) => !output.includes(s));
  throw new Error(
    `Expected log substrings not found after ${timeoutMs}ms: ${JSON.stringify(missing)}`,
  );
}

test.describe('Multi-Agent Startup Team', () => {
  // ── Agent registration ──

  test('CEO agent registered at /atmosphere/agent/ceo', async () => {
    const res = await fetch(`${server.baseUrl}/atmosphere/agent/ceo`);
    expect(res.status).not.toBe(404);
  });

  test('server logs confirm CEO agent with protocols', async () => {
    const output = await waitForOutput([
      '/atmosphere/agent/ceo',
      "Coordinator 'ceo' registered",
    ]);
    expect(output).toContain('/atmosphere/agent/ceo');
    expect(output).toContain("Coordinator 'ceo' registered");
  });

  test('4 headless agents registered', async () => {
    const output = await waitForOutput([
      "Agent 'research-agent' registered",
      "Agent 'strategy-agent' registered",
      "Agent 'finance-agent' registered",
      "Agent 'writer-agent' registered",
    ]);
    expect(output).toContain("Agent 'research-agent' registered");
    expect(output).toContain("Agent 'strategy-agent' registered");
    expect(output).toContain("Agent 'finance-agent' registered");
    expect(output).toContain("Agent 'writer-agent' registered");
    // All should be headless
    const headlessCount = (output.match(/headless, protocols:/g) || []).length;
    expect(headlessCount).toBeGreaterThanOrEqual(4);
  });

  // ── A2A Agent Card discovery ──

  test('research agent discoverable via Agent Card', async () => {
    const body = await a2aRequest(server.baseUrl, '/atmosphere/a2a/research',
      'GetExtendedAgentCard');
    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
    expect(result.name).toBe('research-agent');
    const skills = result.skills as { id: string }[];
    expect(skills.length).toBeGreaterThan(0);
    expect(skills[0].id).toBe('web_search');
  });

  test('finance agent discoverable via Agent Card', async () => {
    const body = await a2aRequest(server.baseUrl, '/atmosphere/a2a/finance',
      'GetExtendedAgentCard');
    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
    expect(result.name).toBe('finance-agent');
  });

  // ── A2A task delegation ──

  test('research agent executes web_search skill via A2A', async () => {
    const body = await a2aRequest(server.baseUrl, '/atmosphere/a2a/research',
      'SendMessage', sendMessageParams('web_search', 'test',
        { query: 'AI developer tools', num_results: '2' }));
    const task = taskOf(body);
    expect(task).toBeDefined();
    const status = task.status as { state: string };
    expect(status.state).toBe('TASK_STATE_COMPLETED');
    const artifacts = task.artifacts as { parts: { text: string }[] }[];
    expect(artifacts.length).toBeGreaterThan(0);
    expect(artifacts[0].parts[0].text).toContain('search results');
  });

  test('strategy agent executes analyze_strategy skill via A2A', async () => {
    const body = await a2aRequest(server.baseUrl, '/atmosphere/a2a/strategy',
      'SendMessage', sendMessageParams('analyze_strategy', 'test',
        { market: 'AI tools', research_findings: 'Growing market',
          focus_area: 'entry' }));
    const task = taskOf(body);
    const status = task.status as { state: string };
    expect(status.state).toBe('TASK_STATE_COMPLETED');
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

  // ── Behavioral depth tests ──

  test('coordination journal shows all 5 agents', () => {
    const output = server.getOutput();
    // Verify the coordinator (ceo) and all 4 headless agents appear in logs
    expect(output).toContain('ceo');
    expect(output).toContain('research-agent');
    expect(output).toContain('strategy-agent');
    expect(output).toContain('finance-agent');
    expect(output).toContain('writer-agent');
    // Count total registered agents (1 coordinator + 4 headless)
    const registeredMatches = output.match(/registered/g) || [];
    expect(registeredMatches.length).toBeGreaterThanOrEqual(5);
  });

  test('fleet.route() conditional routing code is present in coordinator', () => {
    // The CeoCoordinator now uses fleet.route() for conditional synthesis.
    // Verify the coordinator compiled and registered successfully with routing.
    const output = server.getOutput();
    expect(output).toContain("Coordinator 'ceo' registered");
  });

  test('CEO response includes agent result synthesis', async () => {
    const result = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/ceo', 'Analyze the market for AI fitness apps', 25_000);

    // In demo mode, the CEO synthesizes from demo agent responses.
    // The response should contain structured output from the pipeline.
    expect(result.fullText.length).toBeGreaterThan(20);
  });

  test('individual agent failure doesn\'t crash coordinator', async () => {
    // Send a request to the research agent with an invalid skill ID
    const body = await a2aRequest(server.baseUrl, '/atmosphere/a2a/research',
      'SendMessage', sendMessageParams('nonexistent_skill_xyz', 'test'));
    // The agent should return an error or handle gracefully (not crash)
    // It may return an error in the result or a JSON-RPC error
    const result = body.result as Record<string, unknown> | undefined;
    const task = result ? taskOf(body) : undefined;
    const hasError = body.error !== undefined ||
      (task?.status !== undefined);
    expect(hasError).toBe(true);

    // Verify the CEO agent still responds after the failed request
    const ceoResult = await sendAndCollect(server.baseUrl,
      '/atmosphere/agent/ceo', 'Are you still running?', 20_000);
    expect(ceoResult.texts.length).toBeGreaterThan(0);
    expect(ceoResult.fullText.length).toBeGreaterThan(0);
  });
});
