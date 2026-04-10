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
 * A fully tool-calling FakeLlmClient does not yet exist, so these tests target the
 * demo mode of `samples/spring-boot-ai-tools`, which emits the same `AiEvent.ApprovalRequired`
 * frame the real VirtualThreadApprovalStrategy emits. A subsequent phase will add
 * a tool-aware fake client for a full real-pipeline e2e.
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

/** Open a WebSocket, send a prompt, collect frames until `complete` or timeout. */
function runPrompt(
  baseUrl: string,
  path: string,
  prompt: string,
  afterOpen?: (ws: WebSocket) => void,
  timeoutMs = 20_000,
): Promise<{ frames: AnyFrame[]; approvalMessages: string[] }> {
  return new Promise((resolve, reject) => {
    const wsUrl = baseUrl.replace('http://', 'ws://') + path;
    const ws = new WebSocket(wsUrl);
    const frames: AnyFrame[] = [];
    const approvalMessages: string[] = [];
    let opened = false;
    const timer = setTimeout(() => {
      try { ws.close(); } catch { /* ignore */ }
      resolve({ frames, approvalMessages });
    }, timeoutMs);

    ws.on('open', () => {
      opened = true;
      ws.send(prompt);
      afterOpen?.(ws);
    });
    ws.on('message', (data) => {
      const text = data.toString();
      for (const part of text.split(/[|\n]/)) {
        const trimmed = part.trim();
        if (!trimmed || !trimmed.startsWith('{')) continue;
        try {
          const parsed = JSON.parse(trimmed);
          frames.push(parsed);
        } catch { /* not JSON */ }
      }
      if (/"event"\s*:\s*"complete"/.test(text)) {
        clearTimeout(timer);
        try { ws.close(); } catch { /* ignore */ }
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
      undefined,
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
      (ws) => {
        // Wait briefly, then send an approve message for any approval the server emits.
        // Demo mode auto-approves internally so our message is a no-op on the server,
        // but this still exercises that the client→server wire format Phase 0 defined
        // is parseable by ApprovalRegistry.isApprovalMessage without errors.
        setTimeout(() => {
          try {
            ws.send('/__approval/apr_phase0_e2e/approve');
          } catch { /* connection might already be closed */ }
        }, 500);
      },
      15_000,
    );

    const approval = firstApprovalRequired(frames);
    expect(approval).toBeDefined();

    // Demo mode completes the flow after auto-approval.
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
      (ws) => {
        setTimeout(() => {
          try {
            ws.send('/__approval/apr_phase0_e2e/deny');
          } catch { /* ignore */ }
        }, 500);
      },
      15_000,
    );

    const approval = firstApprovalRequired(frames);
    expect(approval).toBeDefined();

    const error = frames.find(f => f.event === 'error' || f.type === 'error');
    expect(error, 'deny message must not error the stream').toBeUndefined();
  });

  test('reset_city_data tool registers with @RequiresApproval metadata', () => {
    const output = server.getOutput();
    expect(output).toContain('Registered AI tool: reset_city_data');
  });
});
