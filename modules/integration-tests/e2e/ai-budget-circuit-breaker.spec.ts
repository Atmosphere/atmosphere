import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8112;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * E2E coverage for the per-call AiBudget circuit breaker — verifies the
 * BudgetCapturingSession decorator routes AiBudgetExceededException
 * through the wire-side error frame on every breach reason, and that
 * post-trip writes are short-circuited so the wire receives a single
 * terminal frame rather than a flurry. Pairs with AiPipelineBudgetTest
 * (unit) — the unit suite covers the decorator semantics directly, this
 * suite proves the same semantics survive Atmosphere's transport.
 */
test.describe('AI Budget Circuit Breaker E2E', () => {

  test('@smoke total-token breach trips with TOTAL_TOKENS reason', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/budget-circuit-breaker');
    try {
      await client.connect();
      // Cap of 100 trips on the second usage event (cumulative 110).
      client.send('total:100');
      await client.waitForDone(15_000);

      expect(client.errors.length).toBeGreaterThan(0);
      const error = client.errors[0];
      expect(error).toContain('AI budget exceeded');
      expect(error).toContain('TOTAL_TOKENS');
      // Post-trip writes do not reach the wire — captured tokens stop
      // before the runtime's "after-trip" send.
      expect(client.tokens.join('')).not.toContain('after-trip');
    } finally {
      client.close();
    }
  });

  test('step breach trips with STEPS reason at exactly N+1', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/budget-circuit-breaker');
    try {
      await client.connect();
      client.send('steps:3');
      await client.waitForDone(15_000);

      expect(client.errors.length).toBeGreaterThan(0);
      expect(client.errors[0]).toContain('STEPS');
      expect(client.errors[0]).toContain('observed=4');
      expect(client.errors[0]).toContain('limit=3');
    } finally {
      client.close();
    }
  });

  test('wall-clock breach trips with WALL_CLOCK reason after deadline', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/budget-circuit-breaker');
    try {
      await client.connect();
      // 50ms cap, runtime sleeps 130ms → trips on the next session call.
      client.send('wallclock:50');
      await client.waitForDone(15_000);

      expect(client.errors.length).toBeGreaterThan(0);
      expect(client.errors[0]).toContain('WALL_CLOCK');
    } finally {
      client.close();
    }
  });

  test('no budget configured = stream completes normally even with huge token counts', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/budget-circuit-breaker');
    try {
      await client.connect();
      client.send('none');
      await client.waitForDone(15_000);

      expect(client.errors).toHaveLength(0);
      expect(client.tokens.join('')).toContain('ok');
    } finally {
      client.close();
    }
  });

  test('per-request budget overrides absent pipeline default', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/budget-circuit-breaker');
    try {
      await client.connect();
      // Pipeline has no default budget, but the per-request metadata
      // carries one — decorator still installs and the breach trips.
      client.send('per-request:total:100');
      await client.waitForDone(15_000);

      expect(client.errors.length).toBeGreaterThan(0);
      expect(client.errors[0]).toContain('TOTAL_TOKENS');
    } finally {
      client.close();
    }
  });
});
