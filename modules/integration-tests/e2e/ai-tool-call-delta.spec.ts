/**
 * toolCallDelta incremental-streaming wire contract (Wave 3) + Gap #8
 * positive/negative capability assertion for AiCapability.TOOL_CALL_DELTA.
 *
 * Built-in's OpenAiCompatibleClient forwards every
 * delta.tool_calls[].function.arguments fragment through
 * StreamingSession.toolCallDelta(id, chunk). The six framework bridges
 * (Spring AI, LangChain4j, ADK, Embabel, Koog, Semantic Kernel) do not —
 * their high-level streaming APIs surface only consolidated tool calls,
 * so they honor the default no-op contract without emitting delta frames.
 * The AiCapability.TOOL_CALL_DELTA enum value pins that distinction on the
 * SPI; the assertions below exercise it against runtime truth via the
 * /ai/capabilities reflection endpoint (CapabilitiesTestHandler).
 */
import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8099;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('toolCallDelta Incremental Streaming (Wave 3)', () => {

  test('@smoke delta chunks arrive before tool-start and concatenate to valid JSON', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/tool-call-delta');
    try {
      await client.connect();
      client.send('stream');
      await client.waitForDone(15_000);

      // Delta chunks arrive as metadata events keyed by "ai.toolCall.delta.tc_001"
      const deltaEvents = client.events.filter(
        e => e.type === 'metadata' && e.key?.startsWith('ai.toolCall.delta.')
      );
      expect(deltaEvents.length).toBeGreaterThanOrEqual(3);

      // Concatenated chunks must form valid JSON
      const concatenated = deltaEvents.map(e => e.value as string).join('');
      const parsed = JSON.parse(concatenated);
      expect(parsed.city).toBe('Montreal');

      // tool-start must arrive after all deltas
      const toolStart = client.events.find(e => e.event === 'tool-start');
      expect(toolStart).toBeDefined();

      const lastDeltaIdx = client.events.lastIndexOf(deltaEvents[deltaEvents.length - 1]);
      const toolStartIdx = client.events.indexOf(toolStart!);
      expect(toolStartIdx).toBeGreaterThan(lastDeltaIdx);

      // tool-result must follow
      const toolResult = client.aiEventData('tool-result');
      expect(toolResult).toBeDefined();
      expect((toolResult!.result as Record<string, unknown>).temp).toBe(22);
    } finally {
      client.close();
    }
  });

  // Gap #8 positive + negative capability assertion. The /ai/capabilities
  // endpoint reflects AgentRuntimeResolver.resolveAll() — the live
  // ServiceLoader discovery path — so these assertions bind to runtime
  // truth (AiCapability enum value, AgentRuntime.capabilities()) rather
  // than prose in the matrix. Only BuiltInAgentRuntime is on the
  // integration-tests classpath, but the per-runtime contract tests in
  // each framework runtime's own module pin their capability sets
  // independently — a future attempt by any framework bridge to declare
  // TOOL_CALL_DELTA without actually forwarding chunks would fail its
  // own contract test at full-build time.
  test('@smoke capability reflection: only Built-in declares TOOL_CALL_DELTA', async () => {
    const response = await fetch(`${server.baseUrl}/ai/capabilities`);
    expect(response.ok).toBeTruthy();

    const payload = await response.json() as {
      runtimes: Array<{ name: string; priority: number; capabilities: string[] }>;
    };

    expect(Array.isArray(payload.runtimes)).toBeTruthy();
    expect(payload.runtimes.length).toBeGreaterThan(0);

    // Positive: Built-in is discovered and declares TOOL_CALL_DELTA.
    const builtIn = payload.runtimes.find(r => r.name === 'built-in');
    expect(builtIn, 'built-in runtime should be discovered via ServiceLoader').toBeDefined();
    expect(builtIn!.capabilities).toContain('TOOL_CALL_DELTA');
    // Also pin the honest co-declarations so drift in either direction
    // breaks the test:
    expect(builtIn!.capabilities).toContain('TEXT_STREAMING');
    expect(builtIn!.capabilities).toContain('TOOL_CALLING');

    // Negative: any runtime discovered OTHER than built-in must NOT
    // declare TOOL_CALL_DELTA. Vacuously true on the integration-tests
    // classpath today (only Built-in is discoverable), but the assertion
    // body itself is the drift detector — if a framework runtime is
    // ever added here and wrongly advertises the capability, this fails.
    const others = payload.runtimes.filter(r => r.name !== 'built-in');
    for (const runtime of others) {
      expect(
        runtime.capabilities,
        `${runtime.name} must not declare TOOL_CALL_DELTA — only BuiltInAgentRuntime`
          + ' forwards delta.tool_calls[].function.arguments chunks through'
          + ' StreamingSession.toolCallDelta()'
      ).not.toContain('TOOL_CALL_DELTA');
    }
  });
});
