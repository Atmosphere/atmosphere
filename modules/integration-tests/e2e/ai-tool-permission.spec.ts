/**
 * End-to-end coverage for the tri-state ToolPermissionPolicy. Each request
 * routes through the live ToolExecutionHelper under a PropertiesToolPermissionPolicy
 * the handler installs per request. The handler reports executor.calls,
 * the cancellation/result JSON, and the count of JFR ToolInvocation events
 * whose outcome is DENIED so the spec can pin both the wire-level effect
 * AND the observability signal in the same round-trip.
 */
import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8109;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('ToolPermissionPolicy — allow / deny tri-state', () => {

  test('@smoke ALLOW lets the executor run and returns its result', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/tool-permission');
    try {
      await client.connect();
      client.send('allow');
      await client.waitForDone(15_000);

      const calls = client.metadata.get('ai.tool.permission.executor.calls') as number;
      const result = client.metadata.get('ai.tool.permission.result') as string;
      const denied = client.metadata.get('ai.tool.permission.jfr.denied') as number;

      expect(calls).toBe(1);
      expect(result).toBe('ok');
      expect(denied).toBe(0);
    } finally {
      client.close();
    }
  });

  test('@smoke DENY short-circuits the executor and emits a JFR DENIED event', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/tool-permission');
    try {
      await client.connect();
      client.send('deny');
      await client.waitForDone(15_000);

      const calls = client.metadata.get('ai.tool.permission.executor.calls') as number;
      const result = client.metadata.get('ai.tool.permission.result') as string;
      const denied = client.metadata.get('ai.tool.permission.jfr.denied') as number;

      expect(calls, 'DENY must not invoke the underlying executor').toBe(0);
      expect(result, 'DENY must return a cancellation JSON').toContain('cancelled');
      expect(result).toContain('ToolPermissionPolicy');
      expect(denied, 'DENY must emit exactly one ToolInvocation JFR event with OUTCOME_DENIED')
        .toBe(1);
    } finally {
      client.close();
    }
  });
});
