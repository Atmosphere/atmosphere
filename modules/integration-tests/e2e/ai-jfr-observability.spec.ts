/**
 * End-to-end coverage for the JFR observability layer added to the AI
 * pipeline. The handler drives one AiPipeline turn under an active JFR
 * Recording and surfaces the recorded event counts as websocket metadata
 * so this spec can assert that the AgentTurn, Call, ToolInvocation, and
 * SessionLifecycle events all fire through the real composed metrics chain
 * — not just from unit-test direct calls.
 */
import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8108;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('JFR observability — agent turn / call / tool / session events', () => {

  test('@smoke pipeline turn emits AgentTurn + Call + ToolInvocation + SessionLifecycle JFR events', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/jfr-observability');
    try {
      await client.connect();
      client.send('go');
      await client.waitForDone(15_000);

      // The handler dumps the per-request recording and emits one metadata
      // frame per event type with the integer count. AgentTurn is the
      // pipeline-driven event; Call/ToolInvocation/SessionLifecycle come from
      // the JfrAiMetrics composite the pipeline installs.
      const agentTurnCount = client.metadata.get('ai.jfr.agentTurn.count') as number | undefined;
      const callCount = client.metadata.get('ai.jfr.call.count') as number | undefined;
      const toolCount = client.metadata.get('ai.jfr.toolInvocation.count') as number | undefined;
      const lifecycleCount = client.metadata.get('ai.jfr.sessionLifecycle.count') as number | undefined;

      expect(agentTurnCount, 'AgentTurn JFR event must fire from AiPipeline').toBeGreaterThanOrEqual(1);
      expect(callCount, 'Call JFR event must fire from JfrAiMetrics.recordLatency')
        .toBeGreaterThanOrEqual(1);
      expect(toolCount, 'ToolInvocation JFR event must fire from JfrAiMetrics.recordToolCall')
        .toBeGreaterThanOrEqual(1);
      // STARTED + ENDED = 2; allow >= 2 in case the pipeline-internal session
      // lifecycle bracket also adds events.
      expect(lifecycleCount,
        'SessionLifecycle pair must fire from JfrAiMetrics.sessionStarted/sessionEnded')
        .toBeGreaterThanOrEqual(2);
    } finally {
      client.close();
    }
  });
});
