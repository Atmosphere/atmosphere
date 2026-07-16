import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

/** Real AG-UI endpoint the @Agent auto-registers (atmosphere-agui on classpath). */
const AGUI_PATH = '/atmosphere/agent/assistant/agui';

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

/** POST to the AG-UI endpoint and collect all SSE events from the response body. */
async function postAgui(baseUrl: string, message: string): Promise<SSEEvent[]> {
  const res = await fetch(`${baseUrl}${AGUI_PATH}`, {
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

// ── DEMO LANE: no LLM key, always runs ────────────────────────────────────
// Drives the real AG-UI native bridge (AgUiHandler → ResourceAgUiStreamingSession
// → AgUiEventMapper) with the DemoResponseProducer fallback. Tools are NOT
// asserted here — the demo path does not call the model, so no real tool
// dispatch happens (that is the REAL-LLM lane below).
test.describe('AG-UI demo lane (no key)', () => {
  test('SSE lifecycle: first RUN_STARTED, last RUN_FINISHED', async () => {
    const events = await postAgui(server.baseUrl, 'Hello!');

    expect(events.length).toBeGreaterThan(0);
    expect(events[0].type).toBe('RUN_STARTED');
    expect(events[events.length - 1].type).toBe('RUN_FINISHED');
  });

  test('streamed text deltas concatenate to the demo phrase', async () => {
    const events = await postAgui(server.baseUrl, 'Hello!');

    const deltas = events
      .filter(e => e.type === 'TEXT_MESSAGE_CONTENT')
      .map(e => e.data.delta as string);
    expect(deltas.length).toBeGreaterThanOrEqual(1);

    // The demo fallback always includes this stable phrase (DemoResponseProducer.DEMO_PHRASE).
    const fullText = deltas.join('');
    expect(fullText).toContain('AG-UI protocol');
  });

  test('text message is framed: one START, one END, stable messageId', async () => {
    const events = await postAgui(server.baseUrl, 'Hello!');

    const starts = events.filter(e => e.type === 'TEXT_MESSAGE_START');
    const ends = events.filter(e => e.type === 'TEXT_MESSAGE_END');
    expect(starts.length).toBe(1);
    expect(ends.length).toBe(1);

    const startId = starts[0].data.messageId as string;
    const contentIds = events
      .filter(e => e.type === 'TEXT_MESSAGE_CONTENT')
      .map(e => e.data.messageId as string);
    for (const id of contentIds) expect(id).toBe(startId);
    expect(ends[0].data.messageId).toBe(startId);
  });
});

// ── REAL-LLM LANE: only when a key is present ──────────────────────────────
// Asserts the model actually dispatches the real @AiTool get_weather and that
// the bridge maps the dispatch to AG-UI TOOL_CALL_* frames.
test.describe('AG-UI real-LLM lane', () => {
  test.skip(!process.env.LLM_API_KEY, 'requires LLM_API_KEY for real tool dispatch');

  test('weather question dispatches the real get_weather tool', async () => {
    const events = await postAgui(server.baseUrl, 'What is the weather in Paris?');

    expect(events[0].type).toBe('RUN_STARTED');
    expect(events[events.length - 1].type).toBe('RUN_FINISHED');

    const toolStart = events.find(
      e => e.type === 'TOOL_CALL_START' && e.data.name === 'get_weather',
    );
    expect(toolStart, 'model should dispatch get_weather').toBeDefined();
    const toolCallId = toolStart!.data.toolCallId as string;

    const toolResult = events.find(
      e => e.type === 'TOOL_CALL_RESULT' && e.data.toolCallId === toolCallId,
    );
    expect(toolResult, 'tool result must match the tool call id').toBeDefined();

    const toolEnd = events.find(
      e => e.type === 'TOOL_CALL_END' && e.data.toolCallId === toolCallId,
    );
    expect(toolEnd, 'tool call must be closed with matching id').toBeDefined();
  });
});

// ── UI LANE: Atmosphere Console driving the AG-UI wire ─────────────────────
// The sample's / redirects to /atmosphere/console/; the Console's ag-ui
// transport adapter (selected via atmosphere.console-transport) posts the
// AG-UI RunContext and renders the named-event SSE stream.
test.describe('AG-UI chat via the Atmosphere Console', () => {
  test('root redirects to the console, connected over ag-ui', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-layout')).toBeVisible();
    await expect(page.getByTestId('chat-input')).toBeVisible();
    // The status pill names the real wire — ag-ui, not the Atmosphere WS.
    await expect(page.getByText('Connected · ag-ui')).toBeVisible({ timeout: 30_000 });
  });

  test('send "Hello!" renders the user message and the streamed assistant text', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByText('Connected · ag-ui')).toBeVisible({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('Hello!');
    await page.getByTestId('chat-send').click();

    // The user's message bubble renders (precise class — the demo reply can
    // echo prompt text, which would break a bare getByText in strict mode).
    await expect(page.locator('.message--user').filter({ hasText: 'Hello!' })).toBeVisible();

    // The streamed assistant reply always contains the stable demo phrase —
    // assert the rendered bubble, not payload bytes.
    await expect(page.locator('.message--assistant').filter({ hasText: 'AG-UI protocol' }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  // Tool-call cards render only when the model dispatches a real tool — that
  // needs a live key, exercised by the real-LLM lane above. The Console maps
  // TOOL_CALL_START/RESULT onto its tool cards via the ag-ui adapter.
  test('weather query shows the get_weather tool card', async ({ page }) => {
    test.skip(!process.env.LLM_API_KEY, 'requires LLM_API_KEY for real tool dispatch');
    await page.goto(server.baseUrl);
    await expect(page.getByText('Connected · ag-ui')).toBeVisible({ timeout: 30_000 });

    await page.getByTestId('chat-input').fill('What is the weather in Paris?');
    await page.getByTestId('chat-send').click();

    await expect(page.getByText('get_weather', { exact: false }).first())
      .toBeVisible({ timeout: 30_000 });
  });

  // Owner: atmosphere-agui; Expiry: 2026-09-30. The demo AG-UI handler responds
  // fast enough that the input disable→enable cycle is not reliably observable
  // in a headless run; revisit with an artificial demo delay or a network-idle
  // wait when the Console gains a "thinking" affordance to assert against.
  test.skip('input disabled while streaming, re-enabled after', async ({ page }) => {
    await page.goto(server.baseUrl);
    await expect(page.getByTestId('chat-input')).toBeVisible();

    await page.getByTestId('chat-input').fill('Hello!');
    await page.getByTestId('chat-send').click();

    await expect(page.getByTestId('chat-input')).toBeDisabled();
    await expect(page.getByTestId('chat-input')).toBeEnabled({ timeout: 30_000 });
  });
});
