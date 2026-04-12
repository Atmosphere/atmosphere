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

test.describe('HITL Approval Real Flow (Gap 9)', () => {

  test('@smoke approve flow: approval-required → approve → tool-result → complete', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/hitl-real');
    try {
      await client.connect();
      client.send('approve-flow');

      // Wait for approval-required event
      const start = Date.now();
      let approvalEvent: Record<string, unknown> | undefined;
      while (Date.now() - start < 10_000) {
        approvalEvent = client.aiEventData('approval-required');
        if (approvalEvent) break;
        await new Promise(r => setTimeout(r, 100));
      }
      expect(approvalEvent).toBeDefined();
      expect(approvalEvent!.toolName).toBe('dangerous_tool');
      expect(approvalEvent!.message).toBe('Confirm dangerous operation?');

      const approvalId = approvalEvent!.approvalId as string;
      expect(approvalId).toBeTruthy();

      // Send approval
      client.send(`approve:${approvalId}`);

      // Wait for completion
      await client.waitForDone(15_000);

      // Verify tool-result arrived after approval
      const toolResult = client.aiEventData('tool-result');
      expect(toolResult).toBeDefined();
      expect((toolResult!.result as Record<string, unknown>).status).toBe('deleted');

      // Verify complete
      expect(client.aiEvents('complete').length).toBe(1);
    } finally {
      client.close();
    }
  });

  test('deny flow: approval-required → deny → complete without tool-result', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/hitl-real');
    try {
      await client.connect();
      client.send('deny-flow');

      // Wait for approval-required event
      const start = Date.now();
      let approvalEvent: Record<string, unknown> | undefined;
      while (Date.now() - start < 10_000) {
        approvalEvent = client.aiEventData('approval-required');
        if (approvalEvent) break;
        await new Promise(r => setTimeout(r, 100));
      }
      expect(approvalEvent).toBeDefined();

      const approvalId = approvalEvent!.approvalId as string;

      // Send denial
      client.send(`deny:${approvalId}`);

      // Wait for completion
      await client.waitForDone(15_000);

      // No tool-result should appear (tool was denied)
      const toolResults = client.aiEvents('tool-result');
      expect(toolResults.length).toBe(0);

      // hitl.denied metadata should be present
      expect(client.metadata.get('hitl.denied')).toBe(true);

      // Complete event still fires
      expect(client.aiEvents('complete').length).toBe(1);
    } finally {
      client.close();
    }
  });
});
