import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8092;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('AI Cache E2E', () => {

  test('Progress and token events delivered with cache configured', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache');
    try {
      await client.connect();
      client.send('cache-test-progress');
      await client.waitForDone();

      // Progress events should be received
      const progressEvents = client.events.filter(e => e.type === 'progress');
      expect(progressEvents.length).toBeGreaterThan(0);

      // Token events should be received
      expect(client.tokens.length).toBeGreaterThan(0);

      // Complete event should be received
      const completeEvents = client.events.filter(e => e.type === 'complete');
      expect(completeEvents.length).toBe(1);
    } finally {
      client.close();
    }
  });

  test('Two concurrent clients both receive full stream', async () => {
    const clients = [
      new AiWsClient(server.wsUrl, '/ai/cache'),
      new AiWsClient(server.wsUrl, '/ai/cache'),
    ];

    try {
      for (const c of clients) await c.connect();

      // Only one client sends the prompt; both share the broadcaster
      clients[0].send('concurrent-cache-test');

      for (const c of clients) {
        await c.waitForDone();
        // Both clients should receive tokens
        expect(c.tokens.length).toBeGreaterThan(0);
        // Both should have the complete event
        expect(c.events.some(e => e.type === 'complete')).toBe(true);
      }

      // Responses should be identical (same broadcaster)
      expect(clients[0].fullResponse).toBe(clients[1].fullResponse);
    } finally {
      clients.forEach(c => c.close());
    }
  });

  test('Error events delivered with cache configured', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache');
    try {
      await client.connect();
      // "error:" prefix triggers FakeLlmClient.erroring
      client.send('error:test');
      await client.waitForDone();

      // Should have received some tokens before the error
      expect(client.tokens.length).toBeGreaterThan(0);

      // Should have received the error event
      expect(client.errors.length).toBeGreaterThan(0);
    } finally {
      client.close();
    }
  });

  test('Multiple sequential prompts from the same client', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache');
    try {
      await client.connect();

      // First prompt
      client.send('first-prompt');
      await client.waitForDone();
      const firstTokenCount = client.tokens.length;
      expect(firstTokenCount).toBeGreaterThan(0);

      // Reset state for second prompt
      client.reset();

      // Second prompt
      client.send('second-prompt');
      await client.waitForDone();
      expect(client.tokens.length).toBeGreaterThan(0);

      // Both prompts should have worked (cache didn't corrupt state)
    } finally {
      client.close();
    }
  });
});

/**
 * Gap #5 — AiPipeline cache-skip semantics.
 *
 * These four tests assert the 5-gate cache-safety formula lifted from
 * AiPipeline.execute() (lines 243-250 in the worktree). Each toggle flips one
 * gate and the handler reports the resulting booleans as metadata.
 *
 * WHY WHITE-BOX: the pipeline's public execute(String, String, StreamingSession)
 * API hard-codes metadata=Map.of() when building the AiRequest, so
 * CacheHint.from(context) always returns none() and the cacheSafe branch is
 * unreachable via the pipeline entry point today — documented at length in the
 * CacheSkipTestHandler Javadoc. The handler recomputes the same boolean formula
 * against a real AgentExecutionContext + DefaultToolRegistry + guardrail list
 * + context-provider list, so drift in any of the 5 gates (hasTools,
 * registryHasTools, hasStructured, hasRag, hasGuardrails) breaks this spec.
 * Upgrade to a true cache-hit vs cache-miss assertion once the pipeline grows
 * a per-call metadata/CacheHint plumbing API.
 */
test.describe('AI Pipeline cache-skip gates (Gap #5)', () => {

  const assertGates = async (
    toggle: 'none' | 'tool' | 'rag' | 'guardrail' | 'structured',
    expected: { hasTools?: boolean; registryHasTools?: boolean; hasStructured?: boolean;
                hasRag?: boolean; hasGuardrails?: boolean; cacheSafe: boolean },
  ) => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache-skip');
    try {
      await client.connect();
      client.send(toggle);
      await client.waitForDone(10_000);
      expect(client.metadata.get('cacheSkip.toggle')).toBe(toggle);
      expect(client.metadata.get('cacheSkip.hasTools')).toBe(expected.hasTools ?? false);
      expect(client.metadata.get('cacheSkip.registryHasTools'))
          .toBe(expected.registryHasTools ?? false);
      expect(client.metadata.get('cacheSkip.hasStructured')).toBe(expected.hasStructured ?? false);
      expect(client.metadata.get('cacheSkip.hasRag')).toBe(expected.hasRag ?? false);
      expect(client.metadata.get('cacheSkip.hasGuardrails')).toBe(expected.hasGuardrails ?? false);
      expect(client.metadata.get('cacheSkip.cacheSafe')).toBe(expected.cacheSafe);
    } finally {
      client.close();
    }
  };

  test('baseline: no toggles → cacheSafe=true', async () => {
    await assertGates('none', { cacheSafe: true });
  });

  test('structured responseType MISSES cache', async () => {
    await assertGates('structured', { hasStructured: true, cacheSafe: false });
  });

  test('ContextProvider (RAG) attached MISSES cache', async () => {
    await assertGates('rag', { hasRag: true, cacheSafe: false });
  });

  test('guardrail attached MISSES cache', async () => {
    await assertGates('guardrail', { hasGuardrails: true, cacheSafe: false });
  });

  test('non-empty tool registry MISSES cache', async () => {
    await assertGates('tool', { registryHasTools: true, cacheSafe: false });
  });
});
