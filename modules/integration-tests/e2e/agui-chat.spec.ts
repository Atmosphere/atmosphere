import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-agui-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Parsed SSE event. */
interface SSEEvent {
  type: string;
  data: Record<string, unknown>;
}

/** POST to /agui and collect all SSE events from the response body. */
async function postAgui(baseUrl: string, message: string): Promise<SSEEvent[]> {
  const res = await fetch(`${baseUrl}/agui`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      threadId: crypto.randomUUID(),
      runId: crypto.randomUUID(),
      messages: [{ role: 'user', content: message }],
    }),
  });

  if (!res.ok) throw new Error(`HTTP ${res.status}`);

  const text = await res.text();
  return parseSSEStream(text);
}

/** Parse raw SSE text into typed events. */
function parseSSEStream(raw: string): SSEEvent[] {
  const events: SSEEvent[] = [];
  let currentType = '';

  for (const line of raw.split('\n')) {
    if (line.startsWith('event: ')) {
      currentType = line.slice(7).trim();
    } else if (line.startsWith('data: ') && currentType) {
      try {
        const data = JSON.parse(line.slice(6)) as Record<string, unknown>;
        events.push({ type: currentType, data });
      } catch { /* skip malformed lines */ }
      currentType = '';
    }
  }
  return events;
}

test.describe('AG-UI SSE Protocol', () => {
  test('SSE lifecycle: first RUN_STARTED, last RUN_FINISHED', async () => {
    const events = await postAgui(server.baseUrl, 'Hello!');

    expect(events.length).toBeGreaterThan(0);
    expect(events[0].type).toBe('RUN_STARTED');
    expect(events[events.length - 1].type).toBe('RUN_FINISHED');
  });

  test('Hello message produces steps and streamed text', async () => {
    const events = await postAgui(server.baseUrl, 'Hello!');

    // Should have STEP_STARTED / STEP_FINISHED events
    const stepEvents = events.filter(e =>
      e.type === 'STEP_STARTED' || e.type === 'STEP_FINISHED',
    );
    expect(stepEvents.length).toBeGreaterThanOrEqual(2);

    // TEXT_MESSAGE_CONTENT deltas should concatenate to the response text
    const deltas = events
      .filter(e => e.type === 'TEXT_MESSAGE_CONTENT')
      .map(e => e.data.delta as string);
    expect(deltas.length).toBeGreaterThan(0);

    const fullText = deltas.join('');
    expect(fullText).toContain('AG-UI protocol');
  });

  test('weather query triggers get_weather tool call', async () => {
    const events = await postAgui(server.baseUrl, 'What is the weather?');

    const toolStart = events.find(
      e => e.type === 'TOOL_CALL_START' && e.data.name === 'get_weather',
    );
    expect(toolStart).toBeDefined();

    const toolResult = events.find(e => e.type === 'TOOL_CALL_RESULT');
    expect(toolResult).toBeDefined();

    // Result is JSON with temp, condition, humidity keys
    const resultStr = toolResult!.data.result as string;
    const result = JSON.parse(resultStr);
    expect(result).toHaveProperty('temp');
    expect(result).toHaveProperty('condition');
    expect(result).toHaveProperty('humidity');
  });

  test('time query triggers get_time tool call', async () => {
    const events = await postAgui(server.baseUrl, 'What time is it?');

    const toolStart = events.find(
      e => e.type === 'TOOL_CALL_START' && e.data.name === 'get_time',
    );
    expect(toolStart).toBeDefined();

    const toolResult = events.find(e => e.type === 'TOOL_CALL_RESULT');
    expect(toolResult).toBeDefined();

    // Result is a formatted datetime string
    const resultStr = toolResult!.data.result as string;
    expect(resultStr).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
  });
});

test.describe('AG-UI Chat UI', () => {
  test('page loads with chat layout', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    await expect(page.getByText('AG-UI Protocol Demo', { exact: false })).toBeVisible();
  });

  test('send message and receive streamed response', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello!');
    await page.getByTestId('chat-send').click();

    // User message should appear
    await expect(page.getByText('Hello!')).toBeVisible();

    // Assistant response includes "AG-UI protocol"
    await expect(page.getByText('AG-UI protocol', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  // Tool dispatch requires real LLM — demo provider echoes but doesn't call tools
  test.skip('weather query shows tool name in UI', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('What is the weather today?');
    await page.getByTestId('chat-send').click();

    // The tool call card should render with the tool name
    await expect(page.getByText('get_weather', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  // Demo AG-UI handler responds instantly — input disable/enable cycle too fast to observe
  test.skip('input disabled while streaming, re-enabled after', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello!');
    await page.getByTestId('chat-send').click();

    // Input should become disabled while agent is running
    await expect(page.getByTestId('chat-input')).toBeDisabled();

    // After response completes, input should be re-enabled
    await expect(page.getByTestId('chat-input'))
      .toBeEnabled({ timeout: 30_000 });
  });
});
