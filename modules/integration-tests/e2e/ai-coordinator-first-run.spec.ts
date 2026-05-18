/**
 * End-to-end coverage for DefaultAgentFleet's opt-in
 * first-run-sequential sub-agent warm-up. The handler drives a real
 * fleet.parallel() over three stub sub-agents and reports peak in-flight
 * concurrency + the JFR SubAgentDispatch.firstRun flag breakdown.
 *
 * - warmup mode  → opt-in ON, peak=1 (sequential), all dispatches tagged firstRun=true
 * - parallel mode → opt-in OFF, peak=3 (parallel), all dispatches tagged firstRun=false
 */
import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8110;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Coordinator first-run-sequential warm-up', () => {

  test('@smoke opt-in ON forces sequential dispatch with firstRun=true JFR events', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/coordinator-first-run');
    try {
      await client.connect();
      client.send('warmup');
      await client.waitForDone(15_000);

      const peak = client.metadata.get('ai.coordinator.firstRun.peakConcurrency') as number;
      const results = client.metadata.get('ai.coordinator.firstRun.resultCount') as number;
      const trueCount = client.metadata.get('ai.coordinator.firstRun.jfr.firstRunTrue') as number;
      const falseCount = client.metadata.get('ai.coordinator.firstRun.jfr.firstRunFalse') as number;

      expect(results, 'all three sub-agents must return a result').toBe(3);
      expect(peak, 'cold-start warm-up must serialize dispatches').toBe(1);
      expect(trueCount,
        'three cold dispatches must each emit a SubAgentDispatch event with firstRun=true')
        .toBe(3);
      expect(falseCount, 'no parallel dispatch should fire on the warm-up path').toBe(0);
    } finally {
      client.close();
    }
  });

  test('@smoke opt-in OFF dispatches in parallel with firstRun=false JFR events', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/coordinator-first-run');
    try {
      await client.connect();
      client.send('parallel');
      await client.waitForDone(15_000);

      const peak = client.metadata.get('ai.coordinator.firstRun.peakConcurrency') as number;
      const results = client.metadata.get('ai.coordinator.firstRun.resultCount') as number;
      const trueCount = client.metadata.get('ai.coordinator.firstRun.jfr.firstRunTrue') as number;
      const falseCount = client.metadata.get('ai.coordinator.firstRun.jfr.firstRunFalse') as number;

      expect(results).toBe(3);
      expect(peak, 'opt-in OFF must dispatch concurrently').toBe(3);
      expect(falseCount,
        'each parallel dispatch must emit a SubAgentDispatch event with firstRun=false')
        .toBe(3);
      expect(trueCount, 'no warm-up event should fire on the parallel path').toBe(0);
    } finally {
      client.close();
    }
  });
});
