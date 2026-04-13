/**
 * toolCallDelta incremental-streaming wire contract (Wave 3) + Gap #8 note.
 *
 * Gap #8 in the phase-2 plan asked for a negative-capability assertion:
 * "for each runtime lacking TOOL_CALL_DELTA in capabilities(), assert ZERO
 * delta frames are emitted even when a tool call is triggered."
 *
 * GROUND TRUTH: TOOL_CALL_DELTA is NOT an AiCapability enum value. Inspection
 * of modules/ai/src/main/java/org/atmosphere/ai/AiCapability.java shows the
 * enumerated set is {TEXT_STREAMING, TOOL_CALLING, STRUCTURED_OUTPUT, VISION,
 * AUDIO, MULTI_MODAL, CONVERSATION_MEMORY, SYSTEM_PROMPT, AGENT_ORCHESTRATION,
 * TOOL_APPROVAL, PROMPT_CACHING, MULTI_AGENT_HANDOFF, CANCELLATION,
 * MODEL_ENUMERATION, TOKEN_USAGE, PER_REQUEST_RETRY}. None of the seven
 * pinned expectedCapabilities() sets in the runtime contract tests include
 * TOOL_CALL_DELTA either. Instead, StreamingSession.toolCallDelta is a
 * default method that any runtime can call; whether a given provider's wire
 * emits incremental tool-argument chunks is a per-bridge behavioural choice
 * (Spring AI, LC4j onPartialToolExecutionRequest, ADK streaming events, Koog
 * StreamFrame.ToolCallDelta), not a declared SPI capability.
 *
 * Consequently the negative-capability assertion in the plan cannot be
 * written against the current code — there is no capability flag to read and
 * AgentRuntimeResolver has no resolve(name) overload. The framework runtime
 * modules are also not on the integration-tests classpath, so we cannot
 * instantiate a spring-ai/adk/koog/embabel/sk/lc4j runtime inside
 * AiFeatureTestServer to observe zero-delta output either. The negative
 * assertion is therefore pinned as a test.skip below with the ground-truth
 * explanation, rather than silently inventing a capability flag or a resolver
 * API that does not exist. The existing positive assertion (delta chunks
 * arrive before tool-start and concatenate to valid JSON) remains live.
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

  // Gap #8 negative-capability assertion — intentionally skipped.
  // See the file header for the ground-truth explanation: TOOL_CALL_DELTA is
  // not an AiCapability enum value, AgentRuntimeResolver has no resolve(name)
  // overload, and framework runtimes are not on the integration-tests
  // classpath. Re-open this test if/when any of those three facts change.
  test.skip('negative capability: runtimes lacking TOOL_CALL_DELTA emit zero deltas',
    async () => {
      // Intentionally empty — see header comment for rationale.
    });
});
