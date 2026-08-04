/*
 * Phase 0 Playwright e2e coverage for the unified HITL approval wire protocol.
 *
 * The unit tests in modules/ai / modules/langchain4j / modules/spring-ai / modules/koog
 * cover the server-side executeWithApproval routing at the bridge seam. These browser
 * + WebSocket tests assert the wire shape clients actually observe — the AiEvent
 * `approval-required` frame must carry every field the Phase 0 contract promises so
 * atmosphere.js UIs can render the prompt and route the user's decision back with
 * the `/__approval/<id>/{approve,deny}` message.
 *
 * These tests target the demo mode of `samples/spring-boot-ai-tools`, whose
 * DemoToolRouter executes the real reset_city_data tool through
 * ToolExecutionHelper.executeWithApproval: the `approval-required` frame is emitted
 * by the real VirtualThreadApprovalStrategy with a real generated apr_* id, the
 * approve/deny replies resolve the session's real ApprovalRegistry, and the tool
 * body only runs after an approve.
 */
import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-ai-tools']);
});

test.afterAll(async () => {
  await server?.stop();
});

interface AnyFrame {
  type?: string;
  event?: string;
  data?: any;
  sessionId?: string;
  seq?: number;
}

/**
 * Open a WebSocket, send a prompt, collect frames until `complete` or timeout.
 * When `onApprovalRequired` is supplied it fires once with the REAL generated
 * approval id from the `approval-required` frame, so tests can drive the live
 * ApprovalRegistry with `/__approval/<real-id>/{approve,deny}`.
 */
function runPrompt(
  baseUrl: string,
  path: string,
  prompt: string,
  onApprovalRequired?: (approvalId: string, ws: WebSocket) => void,
  timeoutMs = 20_000,
): Promise<{ frames: AnyFrame[]; approvalMessages: string[] }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http://', 'ws://') + path;
    const ws = new WebSocket(wsUrl);
    const frames: AnyFrame[] = [];
    const approvalMessages: string[] = [];
    let opened = false;
    let approvalSeen = false;
    let closeScheduled = false;
    const timer = setTimeout(() => {
      try { ws.close(); } catch { /* ignore */ }
      resolve({ frames, approvalMessages });
    }, timeoutMs);

    ws.on('open', () => {
      opened = true;
      ws.send(prompt);
    });
    ws.on('message', (data) => {
      const text = data.toString();
      for (const part of text.split(/[|\n]/)) {
        const trimmed = part.trim();
        if (!trimmed || !trimmed.startsWith('{')) continue;
        try {
          const parsed = JSON.parse(trimmed);
          frames.push(parsed);
          if (!approvalSeen && parsed.event === 'approval-required'
              && parsed.data?.approvalId && onApprovalRequired) {
            approvalSeen = true;
            onApprovalRequired(parsed.data.approvalId as string, ws);
          }
        } catch { /* not JSON */ }
      }
      // The terminal frame is {"type":"complete",...} on the streaming wire
      // (AiEvent-style frames use "event"); accept both so collection ends on
      // the genuine completion instead of riding out the full timeout. Close
      // after a short grace period rather than instantly, so any spurious
      // post-complete frame (e.g. a late error) is still collected for the
      // negative "no error frame" assertions.
      if (!closeScheduled && /"(?:event|type)"\s*:\s*"complete"/.test(text)) {
        closeScheduled = true;
        clearTimeout(timer);
        setTimeout(() => {
          try { ws.close(); } catch { /* ignore */ }
        }, 250);
      }
    });
    ws.on('close', () => {
      clearTimeout(timer);
      resolve({ frames, approvalMessages });
    });
    ws.on('error', (err) => {
      clearTimeout(timer);
      if (!opened) reject(new Error(`WebSocket failed: ${err.message}`));
      else resolve({ frames, approvalMessages });
    });
  });
}

function firstApprovalRequired(frames: AnyFrame[]): any | undefined {
  return frames.find(f => f.event === 'approval-required')?.data;
}

test.describe('Phase 0 — HITL approval wire protocol', () => {
  test('approval-required frame carries the Phase 0 contract fields', async () => {
    const { frames } = await runPrompt(
      server.baseUrl,
      '/atmosphere/ai-chat',
      'Please reset city data for London',
      // Resolve the REAL pending approval so the turn terminates instead of
      // parking until the annotation's expiry; the assertions below are all
      // on the approval-required frame that already arrived.
      (approvalId, ws) => {
        try {
          ws.send(`/__approval/${approvalId}/deny`);
        } catch { /* ignore */ }
      },
      15_000,
    );

    const approval = firstApprovalRequired(frames);
    expect(approval, 'expected an approval-required frame on the wire').toBeDefined();

    // Shape contract — every field VirtualThreadApprovalStrategy promises clients.
    expect(typeof approval.approvalId).toBe('string');
    expect(approval.approvalId.length).toBeGreaterThan(0);
    expect(approval.approvalId).toMatch(/^apr_/);
    expect(approval.toolName).toBe('reset_city_data');
    expect(typeof approval.message).toBe('string');
    expect(approval.message.length).toBeGreaterThan(0);
    expect(approval.message.toLowerCase()).toContain('reset');
    expect(typeof approval.expiresIn).toBe('number');
    expect(approval.expiresIn).toBeGreaterThan(0);
    expect(approval.arguments).toBeDefined();
  });

  test('approve message uses /__approval/<id>/approve format and completes cleanly', async () => {
    const { frames } = await runPrompt(
      server.baseUrl,
      '/atmosphere/ai-chat',
      'Please reset city data for Tokyo',
      // The REAL approval gate parks the tool until the client answers, so
      // approve with the genuine generated id from the approval-required frame
      // — this resolves the live ApprovalRegistry, not a demo auto-approval.
      (approvalId, ws) => {
        try {
          ws.send(`/__approval/${approvalId}/approve`);
        } catch { /* connection might already be closed */ }
      },
      15_000,
    );

    const approval = firstApprovalRequired(frames);
    expect(approval).toBeDefined();

    // The approve resolved the gate, so the REAL tool body executed and its
    // result frame carries the tool's actual output.
    const toolResult = frames.find(f => f.event === 'tool-result');
    expect(toolResult, 'expected a tool-result frame after approval').toBeDefined();
    expect(String(toolResult!.data?.result)).toContain('has been reset');

    // The pipeline completes the turn after the approved tool ran.
    const complete = frames.find(f => f.event === 'complete' || f.type === 'complete');
    expect(complete, 'expected a complete event after approval flow').toBeDefined();

    // No error frames sneaked into the stream.
    const error = frames.find(f => f.event === 'error' || f.type === 'error');
    expect(error).toBeUndefined();
  });

  test('deny message uses /__approval/<id>/deny format without breaking the stream', async () => {
    const { frames } = await runPrompt(
      server.baseUrl,
      '/atmosphere/ai-chat',
      'Please reset city data for Paris',
      // Deny with the genuine generated id — the real gate cancels the tool.
      (approvalId, ws) => {
        try {
          ws.send(`/__approval/${approvalId}/deny`);
        } catch { /* ignore */ }
      },
      15_000,
    );

    const approval = firstApprovalRequired(frames);
    expect(approval).toBeDefined();

    // The denial withholds the destructive tool: its result frame reports the
    // cancellation instead of the tool body's output.
    const toolResult = frames.find(f => f.event === 'tool-result');
    expect(toolResult, 'expected a tool-result frame carrying the cancellation').toBeDefined();
    expect(String(toolResult!.data?.result)).toContain('cancelled');
    expect(String(toolResult!.data?.result)).not.toContain('has been reset');

    // Denial cancels the tool, not the turn: the stream still completes cleanly.
    const complete = frames.find(f => f.event === 'complete' || f.type === 'complete');
    expect(complete, 'expected a complete event after the denied tool').toBeDefined();

    const error = frames.find(f => f.event === 'error' || f.type === 'error');
    expect(error, 'deny message must not error the stream').toBeUndefined();
  });

  test('reset_city_data tool registers with @RequiresApproval metadata', () => {
    const output = server.getOutput();
    expect(output).toContain('Registered AI tool: reset_city_data');
  });
});
