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
 * Gap #5 — AiPipeline cache-skip semantics end-to-end.
 *
 * Before the Bug #2 fix, AiPipeline.execute(clientId, message, session)
 * hardcoded AiRequest.metadata() to Map.of(), which meant CacheHint.from
 * always returned none() and the entire cacheSafe branch was unreachable via
 * the public API. The previous version of these tests had to recompute the
 * 5-gate formula white-box inside a test handler because no real cache hit
 * could ever fire.
 *
 * Now that AiPipeline.setDefaultCachePolicy and the 4-arg execute() overload
 * plumb CacheHint through to the request metadata, CacheSkipTestHandler
 * instantiates a real AiPipeline + InMemoryResponseCache per request, runs
 * the pipeline TWICE with identical prompts, and publishes each run's
 * observed ai.cache.hit metadata value. These tests assert the framework
 * actually hits/misses/skips the cache:
 *
 *   - Baseline toggle (none): run1 emits ai.cache.hit=false (miss + store),
 *     run2 emits ai.cache.hit=true (served from cache). Runtime is invoked
 *     exactly once across both runs.
 *   - Toggle flips any of {tool, rag, guardrail, structured}: gate short-
 *     circuits before the cache is consulted so no ai.cache.hit frame is
 *     emitted at all. Both run1 and run2 go through the runtime.
 */
test.describe('AI Pipeline cache-skip gates (Gap #5)', () => {

  const runToggle = async (
    toggle: 'none' | 'tool' | 'rag' | 'guardrail' | 'structured',
    expected: {
      run1Hit: boolean | 'absent';
      run2Hit: boolean | 'absent';
      run1RuntimeCalls: number;
      run2RuntimeCalls: number;
    },
  ) => {
    const client = new AiWsClient(server.wsUrl, '/ai/cache-skip');
    try {
      await client.connect();
      // Prompt body is fixed so identical inputs produce identical cache keys
      // on the baseline toggle.
      client.send(`${toggle} same-prompt-body`);
      await client.waitForDone(10_000);

      expect(client.metadata.get('cacheSkip.toggle')).toBe(toggle);
      expect(client.metadata.get('cacheSkip.run1.hit')).toBe(expected.run1Hit);
      expect(client.metadata.get('cacheSkip.run2.hit')).toBe(expected.run2Hit);
      expect(client.metadata.get('cacheSkip.run1.runtimeCalls')).toBe(expected.run1RuntimeCalls);
      expect(client.metadata.get('cacheSkip.run2.runtimeCalls')).toBe(expected.run2RuntimeCalls);
    } finally {
      client.close();
    }
  };

  test('baseline: identical prompts HIT cache on second run', async () => {
    await runToggle('none', {
      run1Hit: false,       // gate opens, cache misses, store
      run2Hit: true,        // gate opens, cache hits, replay
      run1RuntimeCalls: 1,  // runtime fired once on miss
      run2RuntimeCalls: 1,  // runtime NOT called on hit
    });
  });

  test('structured responseType SKIPS cache entirely (no frame)', async () => {
    await runToggle('structured', {
      run1Hit: 'absent',
      run2Hit: 'absent',
      run1RuntimeCalls: 1,
      run2RuntimeCalls: 2,
    });
  });

  test('ContextProvider (RAG) SKIPS cache entirely (no frame)', async () => {
    await runToggle('rag', {
      run1Hit: 'absent',
      run2Hit: 'absent',
      run1RuntimeCalls: 1,
      run2RuntimeCalls: 2,
    });
  });

  test('guardrail attached SKIPS cache entirely (no frame)', async () => {
    await runToggle('guardrail', {
      run1Hit: 'absent',
      run2Hit: 'absent',
      run1RuntimeCalls: 1,
      run2RuntimeCalls: 2,
    });
  });

  test('non-empty tool registry SKIPS cache entirely (no frame)', async () => {
    await runToggle('tool', {
      run1Hit: 'absent',
      run2Hit: 'absent',
      run1RuntimeCalls: 1,
      run2RuntimeCalls: 2,
    });
  });
});
