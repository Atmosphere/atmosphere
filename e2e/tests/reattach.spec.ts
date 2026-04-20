import { expect, test } from '@playwright/test';
import { request as httpRequest } from 'node:http';
import { URL } from 'node:url';

/**
 * E2E reattach contract: a client connecting to an Atmosphere
 * `@AiEndpoint` with `X-Atmosphere-Run-Id` set must receive every event
 * the replay buffer captured against that run id. Exercises the full
 * production pipeline:
 *
 *   RunRegistryHolder.register (producer)
 *     → RunEventReplayBuffer.capture (buffer populated)
 *     → [client reconnects with X-Atmosphere-Run-Id]
 *     → AiEndpointHandler.onReady → reattachPendingRun
 *     → RunReattachSupport.replayPendingRun (drains buffer → resource.write)
 *     → transport flushes bytes to the client
 *
 * Unit tests (`RunReattachSupportTest`, `RunEventCapturingSessionTest`,
 * `AgentResumeHandleTest`) pin every primitive individually and walk
 * the capture → disconnect → reconnect → replay loop with a real
 * registry. They cannot prove the **transport** layer cooperates —
 * that the servlet container preserves the header onto the request
 * attribute chain, that `AtmosphereResource` lifecycle events fire in
 * the right order, and that the broadcaster delivers the replay writes
 * through to the reconnected HTTP response. This spec closes that gap
 * by driving the `spring-boot-reattach-harness` sample over real HTTP.
 *
 * REQUIRES: `samples/spring-boot-reattach-harness` running on port 8096.
 *   cd samples/spring-boot-reattach-harness
 *   ../../mvnw spring-boot:run
 *
 * Why `synthetic-run` instead of a live `@Prompt` disconnect:
 * the reattach contract is about the header-to-replay wire, not
 * wall-clock timing. `SyntheticRunController` pre-populates a run's
 * replay buffer with known events and returns the run id; the reconnect
 * path goes through the identical production code
 * (`AiEndpointHandler.onReady → replayPendingRun`). The live-`@Prompt`
 * surface (`SlowEmitterChat` at `/atmosphere/agent/harness`) is there
 * for manual verification; this spec uses the synthetic surface for CI
 * reliability.
 */
test.describe('Mid-stream reattach via X-Atmosphere-Run-Id', () => {
  test.use({ baseURL: process.env.ATMO_E2E_BASE_URL ?? 'http://localhost:8096' });

  test('synthetic run — reconnect replays captured events to new resource',
      async ({ request, baseURL }) => {
    // Step 1 — pre-register a run with known events via the harness REST
    // surface. This lands entries in RunRegistryHolder.get() exactly as
    // AiEndpointHandler.invokePrompt does for a real @Prompt turn; the
    // reconnect path consults the same registry.
    const runRes = await request.post('/harness/synthetic-run');
    expect(runRes.ok(),
        'harness /harness/synthetic-run must succeed — ensure the reattach-harness '
        + 'sample is running on port 8096 (set ATMO_E2E_BASE_URL to override)')
        .toBeTruthy();
    const payload = await runRes.json();
    expect(payload.events).toEqual(['replay-event-0', 'replay-event-1', 'replay-event-2']);
    const runId = payload.runId as string;
    expect(runId, 'harness must return a non-empty run id').toBeTruthy();

    // Step 2 — connect to the @AiEndpoint carrying X-Atmosphere-Run-Id.
    // AiEndpointHandler.onReady fires reattachPendingRun, which hands
    // the request to RunReattachSupport.replayPendingRun. That drains
    // the 3 text + 1 complete event from the buffer onto the resource.
    //
    // Long-polling keeps the connection open after the replay, so we
    // use a raw fetch with AbortController and a short timeout — the
    // replay lands well before the cap and the bytes we pull are what
    // we assert on.
    const body = await fetchWithAbort(
        `${baseURL}/atmosphere/agent/harness/?X-Atmosphere-Transport=long-polling`,
        runId,
        8_000);

    // Step 3 — every captured event must surface as a proper
    // AiStreamMessage JSON frame on the reconnecting resource. The
    // substring presence check alone was too weak — it passes even if
    // the replay path drops the JSON envelope, which breaks the
    // frontend parser. Assert the schema explicitly.
    const frames = parseJsonFrames(body);
    expect(frames.length,
        `expected >=4 replay frames (3 text + 1 complete), got ${frames.length}: ${body.slice(0, 400)}`)
        .toBeGreaterThanOrEqual(4);

    const textFrames = frames.filter((f) => f.type === 'streaming-text');
    expect(textFrames.length, 'three streaming-text frames must replay').toBe(3);
    expect(textFrames.map((f) => f.data))
        .toEqual(['replay-event-0', 'replay-event-1', 'replay-event-2']);

    const completeFrame = frames.find((f) => f.type === 'complete');
    expect(completeFrame, 'terminal complete envelope must replay').toBeDefined();
    expect(completeFrame?.data).toBe('synthetic');

    for (const f of frames.slice(0, 4)) {
      expect(f.sessionId, 'every replay frame must carry sessionId for client correlation')
          .toBe(runId);
    }
  });

  test('synthetic error run — reconnect replays text + error envelope',
      async ({ request, baseURL }) => {
    // Terminal-error replay is the other half of the contract: a
    // turn that threw or timed out on the server must surface an
    // error envelope to the reconnecting client so the UI stops
    // spinning. Prior to the P1 fix that wired
    // AiEndpointHandler's catch / timeout paths through the
    // capturing session, this would have surfaced partial text with
    // no terminator.
    const runRes = await request.post('/harness/synthetic-error-run');
    expect(runRes.ok()).toBeTruthy();
    const { runId } = await runRes.json();
    expect(runId).toBeTruthy();

    const body = await fetchWithAbort(
        `${baseURL}/atmosphere/agent/harness/?X-Atmosphere-Transport=long-polling`,
        runId, 5_000);

    const frames = parseJsonFrames(body);
    const textFrame = frames.find((f) => f.type === 'streaming-text');
    const errorFrame = frames.find((f) => f.type === 'error');
    expect(textFrame, 'partial text before failure must replay').toBeDefined();
    expect(textFrame?.data).toBe('partial before failure');
    expect(errorFrame, 'terminal error envelope must replay so client stops spinning').toBeDefined();
    expect(errorFrame?.data).toContain('timed out');
  });

  test('unknown run id — connection still succeeds, zero replay events',
      async ({ baseURL }) => {
    // A reconnect carrying a run id the registry never saw must be a
    // silent no-op — the endpoint still accepts the connection (the run
    // may have expired; treat as a fresh session) but writes nothing
    // from a replay buffer.
    const body = await fetchWithAbort(
        `${baseURL}/atmosphere/agent/harness/?X-Atmosphere-Transport=long-polling`,
        'never-seen-runId',
        2_000);
    expect(body,
        'unknown run id must not trigger replay writes — the run may have '
        + 'expired; treat as a fresh session').not.toContain('replay-event');
  });
});

/**
 * Parse the response body into individual AiStreamMessage frames.
 * Atmosphere's long-polling response mixes ~2KB of padding (spaces /
 * middle-dots used as the browser-flush primer) followed by
 * newline-delimited JSON lines. Strip the padding and split on newlines,
 * keeping only lines that parse as a JSON object with a `type` field —
 * ignore any raw residue the transport may have added.
 */
function parseJsonFrames(body: string): Array<{ type: string; data?: string; sessionId?: string; seq?: number }> {
  const out: Array<{ type: string; data?: string; sessionId?: string; seq?: number }> = [];
  for (const line of body.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) {
      continue;
    }
    try {
      const parsed = JSON.parse(trimmed);
      if (typeof parsed?.type === 'string') {
        out.push(parsed);
      }
    } catch {
      // Ignore non-JSON lines — padding / keepalive.
    }
  }
  return out;
}

/**
 * Long-polling intentionally keeps the connection open after the first
 * flush, so `fetch(...)` buffers the body until connection close and
 * aborting mid-stream discards whatever partial bytes arrived. Drop to
 * Node's raw `http.request` so we can accumulate chunks as they land
 * and destroy the socket when our cap elapses — keeping what actually
 * came back on the wire, which is the replay payload.
 */
function fetchWithAbort(url: string, runId: string, ms: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const parsed = new URL(url);
    const req = httpRequest({
      hostname: parsed.hostname,
      port: parsed.port,
      path: parsed.pathname + parsed.search,
      method: 'GET',
      headers: { 'X-Atmosphere-Run-Id': runId },
    }, (res) => {
      let body = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { body += chunk; });
      res.on('end', () => resolve(body));
      res.on('close', () => resolve(body));
    });
    req.on('error', (e: any) => {
      if (e?.code === 'ECONNRESET' || e?.code === 'UND_ERR_SOCKET') {
        resolve('');
      } else {
        reject(e);
      }
    });
    const timer = setTimeout(() => {
      // Destroy the socket after the cap — whatever bytes landed on
      // the 'data' handler are in `body` and returned via the 'close'
      // listener above.
      req.destroy();
    }, ms);
    // Ensure the timer doesn't keep the test runner alive.
    timer.unref?.();
    req.end();
  });
}
