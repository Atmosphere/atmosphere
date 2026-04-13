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

/**
 * Wire-level regression matrix for ExecutionHandle.cancel() across the five
 * AgentRuntime implementations that override the default cancel behavior:
 * Built-in, Spring AI, LangChain4j, ADK, Koog. Semantic Kernel and Embabel
 * intentionally no-op cancel and are NOT in this matrix.
 *
 * Motivation: ~5 bugs patched in the past week sit on the cancel path
 *   - c29542f1e6  ExecutionHandle.Settable.cancel logs at TRACE
 *   - 4ca8e983d8  LC4j drops post-cancel errors (Settable first-writer reason)
 *   - 800cc7e73d  LC4j cancel() resolves future exceptionally
 *   - 7e2a24a986  ADK event adapter completes whenDone exceptionally on errors
 *   - cda1862619  Koog cancel unhangs via interrupt + synthetic completion
 *
 * Unit tests cover each runtime's native cancel primitive. This spec is the
 * wire-level guard: cancel must produce exactly one terminal frame, must not
 * leak any post-cancel streaming-text or error frame, and must leave the
 * session usable for the next request on the same WebSocket.
 *
 * Built-in is exercised end-to-end via a real BuiltInAgentRuntime instance
 * with a cancellation-aware slow LlmClient — so BuiltInAgentRuntime's
 * stream-close + cancel-flag wiring is on the hot path for that row.
 *
 * The four framework runtime rows share a handler that wraps the same slow
 * client in an ExecutionHandle.Settable with an interrupt-based native cancel
 * — the same Settable helper each real runtime uses. This asserts the
 * handler/session/wire contract does not regress; native primitives are
 * covered by unit tests in modules/{spring-ai,langchain4j,adk,koog}/src/test.
 */
const RUNTIMES = [
  'built-in',
  'spring-ai',
  'langchain4j',
  'adk',
  'koog',
] as const;

/**
 * Cancel terminal-frame deadline. 1500ms is generous: the server awaits the
 * handle for up to 1500ms on the server side, and the client leaves ~500ms
 * of slack for the wire + broadcast roundtrip. Relaxed from the original
 * 500ms target because virtual-thread interrupt observation can take a
 * full token tick (100ms) plus broadcaster dispatch.
 */
const CANCEL_DEADLINE_MS = 2000;

/** How long to wait AFTER the terminal frame to check for leaking frames. */
const POST_CANCEL_QUIET_MS = 500;

test.describe('ExecutionHandle.cancel() Wire-Level Matrix', () => {

  for (const runtime of RUNTIMES) {
    test(`@smoke ${runtime} cancel fires terminal frame and leaks no post-cancel frames`, async () => {
      const client = new AiWsClient(server.wsUrl, `/ai/cancel/${runtime}`);
      try {
        await client.connect();

        // 1. Start the long-running stream.
        client.send('slow');

        // 2. Wait ~200ms so at least a couple tokens have been emitted
        //    (the spec asserts partial streaming before cancel).
        await new Promise((r) => setTimeout(r, 250));
        const tokensBeforeCancel = client.tokens.length;
        expect(tokensBeforeCancel, `${runtime}: expected at least one token before cancel`)
          .toBeGreaterThan(0);

        // 3. Client-side cancel.
        const cancelSentAt = Date.now();
        client.send('cancel');

        // 4. Terminal frame must arrive within the deadline.
        await client.waitForDone(CANCEL_DEADLINE_MS);
        const terminalAt = Date.now();
        const cancelElapsed = terminalAt - cancelSentAt;
        expect(cancelElapsed,
          `${runtime}: cancel→terminal took ${cancelElapsed}ms (> ${CANCEL_DEADLINE_MS}ms)`)
          .toBeLessThanOrEqual(CANCEL_DEADLINE_MS);

        // 5. Exactly one terminal frame, and it is a 'complete' (not 'error').
        const completes = client.events.filter((e) => e.type === 'complete');
        const errors = client.events.filter((e) => e.type === 'error');
        expect(completes.length,
          `${runtime}: expected exactly one complete frame, got ${completes.length}`)
          .toBe(1);
        expect(errors.length,
          `${runtime}: cancel must not synthesize an error frame, got ${errors.length}`)
          .toBe(0);

        // 6. Frozen-counter check: no new streaming-text / error frames after
        //    the terminal. Record counts, wait POST_CANCEL_QUIET_MS, re-check.
        const tokensAtTerminal = client.tokens.length;
        const eventsAtTerminal = client.events.length;
        await new Promise((r) => setTimeout(r, POST_CANCEL_QUIET_MS));
        expect(client.tokens.length,
          `${runtime}: streaming-text frame leaked ${client.tokens.length - tokensAtTerminal} frames after cancel`)
          .toBe(tokensAtTerminal);
        const leakedTypes = client.events.slice(eventsAtTerminal)
          .map((e) => e.type)
          .filter((t) => t === 'streaming-text' || t === 'error');
        expect(leakedTypes,
          `${runtime}: post-cancel frame leak: ${JSON.stringify(leakedTypes)}`)
          .toEqual([]);

        // 7. Runtime label must have been echoed as metadata before cancel —
        //    confirms the handler wired the matrix row, not a stale route.
        expect(client.metadata.get('runtime'), `${runtime}: metadata.runtime`)
          .toBe(runtime);
        expect(client.metadata.get('phase'), `${runtime}: metadata.phase`)
          .toBe('slow');

        // 8. Session must still be usable: reset the client-side event log
        //    (NOT the socket) and send a fresh 'fast' prompt. The server
        //    handler allocates a new StreamingSession keyed to the same
        //    resource, so cancel-state leakage would show up as missing
        //    tokens or a duplicate terminal.
        client.reset();
        client.send('fast');
        await client.waitForDone(10_000);
        expect(client.tokens.length,
          `${runtime}: follow-up 'fast' prompt must emit tokens`)
          .toBeGreaterThan(0);
        const followUpCompletes = client.events.filter((e) => e.type === 'complete');
        const followUpErrors = client.events.filter((e) => e.type === 'error');
        expect(followUpCompletes.length,
          `${runtime}: follow-up 'fast' prompt must emit exactly one complete`)
          .toBe(1);
        expect(followUpErrors.length,
          `${runtime}: follow-up 'fast' prompt must not emit error`)
          .toBe(0);
        expect(client.metadata.get('phase'),
          `${runtime}: follow-up phase metadata`)
          .toBe('fast');
      } finally {
        client.close();
      }
    });
  }
});
