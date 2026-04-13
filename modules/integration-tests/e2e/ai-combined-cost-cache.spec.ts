import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8097;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Combined Cost/Latency Routing + Cache Coalescing E2E', () => {

  test('Cost routing tokens are cached and coalesced event fires', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/combined-cost-cache');
    try {
      await client.connect();
      // Budget 10: maxTokens = 10000
      // premium 0.01*10000=100 > 10, mid 0.005*10000=50 > 10, cheap 0.001*10000=10 <= 10
      client.send('cost:10 test cost with cache');
      await client.waitForDone();

      // Routing should pick cheap-model
      const routingModel = await client.waitForMetadata('routing.model');
      expect(routingModel).toBe('cheap-model');
      expect(client.fullResponse).toContain('CHEAP:');

      // Tokens should have been delivered (3 tokens from cheap-model)
      expect(client.tokens.length).toBe(3);

      // Complete event should fire exactly once
      const completeEvents = client.events.filter(e => e.type === 'complete');
      expect(completeEvents.length).toBe(1);

      // Coalesced event should have fired with correct token count
      const output = server.getOutput();
      expect(output).toContain('COMBINED_COALESCED:');
      expect(output).toContain(':streamingTexts=3:');
      expect(output).toContain(':status=complete:');
    } finally {
      client.close();
    }
  });

  test('Latency routing tokens are cached and coalesced event fires', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/combined-cost-cache');
    try {
      await client.connect();
      // Max latency 100ms: slow 500>100, medium 150>100, fast 30<=100
      client.send('latency:100 test latency with cache');
      await client.waitForDone();

      // Routing should pick fast-model
      const routingModel = await client.waitForMetadata('routing.model');
      expect(routingModel).toBe('fast-model');
      expect(client.fullResponse).toContain('FAST:');

      // Latency metadata should be present
      const routingLatency = await client.waitForMetadata('routing.latency');
      expect(routingLatency).toBe(30);

      // Tokens should have been delivered (3 tokens from fast-model)
      expect(client.tokens.length).toBe(3);

      // Coalesced event should have fired
      const output = server.getOutput();
      expect(output).toContain(':status=complete:');
    } finally {
      client.close();
    }
  });

  // Phase 3 nice-to-have — TokenUsage.cachedInput split assertion.
  //
  // GROUND TRUTH: TokenUsage.cachedInput is plumbed in OpenAiCompatibleClient
  // via the OpenAI prompt_tokens_details.cached_tokens JSON field, surfaces
  // on the wire as ai.tokens.cached_input, and the pipeline ResponseCache
  // hit path calls target.usage(cached.usage()) which replays the stored
  // TokenUsage exactly — so a real OpenAI-backed run that populates
  // cached_tokens and then hits the pipeline cache WOULD assert a non-zero
  // ai.tokens.cached_input value. However this specific endpoint
  // (/ai/combined-cost-cache) exercises the BroadcasterCache listener
  // coalescing path with FakeLlmClient fixtures that never emit a
  // cachedInput > 0 TokenUsage, and it does not install a pipeline
  // ResponseCache. The assertion would be a no-op against this handler.
  //
  // Documented as a skip to avoid rot: if a future handler wires a
  // FakeLlmClient variant that emits cachedInput>0 (or if this endpoint
  // gains a pipeline cache), flip the skip off and add the assertion.
  test.skip('TokenUsage.cachedInput replayed on pipeline cache hit (requires real-LLM or fake-with-cached_tokens)', async () => {
    // Assertion shape once plumbed end-to-end:
    //   expect(firstClient.metadata.get('ai.tokens.cached_input')).toBeGreaterThan(0);
    //   expect(secondClient.metadata.get('ai.tokens.cached_input'))
    //       .toBe(firstClient.metadata.get('ai.tokens.cached_input'));
  });

  test('Sequential cost then latency requests each produce a coalesced event', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/combined-cost-cache');
    try {
      await client.connect();

      // First request: cost routing -> cheap-model (3 tokens)
      client.send('cost:10 first request');
      await client.waitForDone();
      expect(client.fullResponse).toContain('CHEAP:');
      const firstTokenCount = client.tokens.length;
      expect(firstTokenCount).toBe(3);

      // Reset client state for second request
      client.reset();

      // Second request: latency routing -> fast-model (3 tokens)
      client.send('latency:100 second request');
      await client.waitForDone();
      expect(client.fullResponse).toContain('FAST:');
      expect(client.tokens.length).toBe(3);

      // Server output should contain two COMBINED_COALESCED lines
      const output = server.getOutput();
      const coalescedMatches = output.match(/COMBINED_COALESCED:/g);
      expect(coalescedMatches).toBeTruthy();
      expect(coalescedMatches!.length).toBeGreaterThanOrEqual(2);
    } finally {
      client.close();
    }
  });
});
