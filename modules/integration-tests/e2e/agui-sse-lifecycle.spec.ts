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

test.describe('AG-UI SSE Lifecycle', () => {

  test('SSE stream starts with RUN_STARTED and ends with RUN_FINISHED', async () => {
    const events = await postAgui(server.baseUrl, 'Hello lifecycle!');

    expect(events.length).toBeGreaterThan(0);
    expect(events[0].type).toBe('RUN_STARTED');
    expect(events[events.length - 1].type).toBe('RUN_FINISHED');
  });

  test('SSE events arrive in correct lifecycle order', async () => {
    const events = await postAgui(server.baseUrl, 'Test ordering');

    // RUN_STARTED must come first
    expect(events[0].type).toBe('RUN_STARTED');

    // Find step events
    const stepStartIdx = events.findIndex(e => e.type === 'STEP_STARTED');
    const stepFinishIdx = events.findIndex(e => e.type === 'STEP_FINISHED');
    const runFinishIdx = events.findIndex(e => e.type === 'RUN_FINISHED');

    if (stepStartIdx >= 0 && stepFinishIdx >= 0) {
      // STEP_STARTED must come before STEP_FINISHED
      expect(stepStartIdx).toBeLessThan(stepFinishIdx);
    }

    // RUN_FINISHED must be last
    expect(runFinishIdx).toBe(events.length - 1);
  });

  test('TEXT_MESSAGE_CONTENT deltas form complete response', async () => {
    const events = await postAgui(server.baseUrl, 'Hello!');

    const deltas = events
      .filter(e => e.type === 'TEXT_MESSAGE_CONTENT')
      .map(e => e.data.delta as string);

    expect(deltas.length).toBeGreaterThan(0);

    const fullText = deltas.join('');
    expect(fullText.length).toBeGreaterThan(0);
  });

  test('sequential requests get independent SSE streams', async () => {
    const events1 = await postAgui(server.baseUrl, 'First request');
    const events2 = await postAgui(server.baseUrl, 'Second request');

    // Both should have complete lifecycle
    expect(events1[0].type).toBe('RUN_STARTED');
    expect(events1[events1.length - 1].type).toBe('RUN_FINISHED');
    expect(events2[0].type).toBe('RUN_STARTED');
    expect(events2[events2.length - 1].type).toBe('RUN_FINISHED');
  });

  test('concurrent SSE requests do not interfere', async () => {
    const [events1, events2] = await Promise.all([
      postAgui(server.baseUrl, 'Concurrent A'),
      postAgui(server.baseUrl, 'Concurrent B'),
    ]);

    // Both should complete independently
    expect(events1.length).toBeGreaterThan(0);
    expect(events2.length).toBeGreaterThan(0);
    expect(events1[0].type).toBe('RUN_STARTED');
    expect(events2[0].type).toBe('RUN_STARTED');
    expect(events1[events1.length - 1].type).toBe('RUN_FINISHED');
    expect(events2[events2.length - 1].type).toBe('RUN_FINISHED');
  });

  test('tool call events are properly nested within steps', async () => {
    const events = await postAgui(server.baseUrl, 'What is the weather?');

    const toolStart = events.find(e => e.type === 'TOOL_CALL_START');
    const toolResult = events.find(e => e.type === 'TOOL_CALL_RESULT');

    if (toolStart && toolResult) {
      const toolStartIdx = events.indexOf(toolStart);
      const toolResultIdx = events.indexOf(toolResult);

      // Tool result must come after tool start
      expect(toolResultIdx).toBeGreaterThan(toolStartIdx);

      // Both should be within step boundaries
      const stepStartIdx = events.findIndex(e => e.type === 'STEP_STARTED');
      let stepFinishIdx = -1;
      for (let i = events.length - 1; i >= 0; i--) {
        if (events[i].type === 'STEP_FINISHED') { stepFinishIdx = i; break; }
      }

      if (stepStartIdx >= 0 && stepFinishIdx >= 0) {
        expect(toolStartIdx).toBeGreaterThan(stepStartIdx);
        expect(toolResultIdx).toBeLessThan(stepFinishIdx);
      }
    }
  });
});
