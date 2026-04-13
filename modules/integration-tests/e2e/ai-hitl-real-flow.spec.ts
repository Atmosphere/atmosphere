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

  test('cross-session approval ID submitted by a different session falls through as UNKNOWN_ID', async () => {
    // Gap #9 — fix 0db97e3276 made AiPipeline.tryResolveApproval and
    // AiStreamingSession.tryResolveApproval short-circuit only on RESOLVED so
    // a stale / cross-session approval ID does not silently swallow a prompt.
    // Each session owns its own ApprovalRegistry; session A registers, session B
    // submits A's id and expects UNKNOWN_ID, not RESOLVED.
    const sessionA = new AiWsClient(server.wsUrl, '/ai/hitl-cross-session');
    const sessionB = new AiWsClient(server.wsUrl, '/ai/hitl-cross-session');
    try {
      await sessionA.connect();
      await sessionB.connect();

      // Session A: register a pending approval, capture the generated ID.
      sessionA.send('register');
      await sessionA.waitForDone(10_000);
      const approvalId = sessionA.metadata.get('hitl.cross.approvalId') as string;
      expect(approvalId).toBeTruthy();
      expect(approvalId).toMatch(/^apr_/);

      // Session B: submit A's ID via the ApprovalRegistry wire protocol.
      // B's registry is a distinct instance and never held that ID.
      sessionB.send(`/__approval/${approvalId}/approve`);
      await sessionB.waitForDone(10_000);

      // The fix forces the tri-state to expose UNKNOWN_ID so the caller can
      // decide to forward the message to the pipeline instead of swallowing.
      expect(sessionB.metadata.get('hitl.cross.resolveResult')).toBe('UNKNOWN_ID');

      // Sanity: a plain non-approval message returns NOT_APPROVAL_MESSAGE,
      // proving the tri-state is actually being exercised end-to-end.
      const sessionC = new AiWsClient(server.wsUrl, '/ai/hitl-cross-session');
      try {
        await sessionC.connect();
        sessionC.send('hello');
        await sessionC.waitForDone(10_000);
        expect(sessionC.metadata.get('hitl.cross.resolveResult')).toBe('NOT_APPROVAL_MESSAGE');
      } finally {
        sessionC.close();
      }
    } finally {
      sessionA.close();
      sessionB.close();
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
