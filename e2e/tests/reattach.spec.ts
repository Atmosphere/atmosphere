import { expect, test } from '@playwright/test';

/**
 * E2E reattach contract: a client that disconnects mid-stream and
 * reconnects carrying `X-Atmosphere-Run-Id` must receive every event
 * the replay buffer captured while it was gone. Exercises the full
 * pipeline:
 *
 *   AiEndpointHandler.invokePrompt (producer: registers the run)
 *     → RunRegistry / RunEventReplayBuffer (capture live events)
 *     → [client disconnects]
 *     → [client reconnects with X-Atmosphere-Run-Id]
 *     → DurableSessionInterceptor (stashes header into request attribute)
 *     → AiEndpointHandler.onReady → reattachPendingRun
 *     → RunReattachSupport.replayPendingRun (drains buffer → resource.write)
 *
 * The unit test `RunReattachSupportTest` pins the replay semantics
 * deterministically; this spec is the last mile — proving the real
 * transport, real broadcaster, and real interceptor chain all cooperate.
 *
 * Skipped by default. Set `REATTACH_SAMPLE_URL` to a sample that:
 *
 *   1. Exposes an `@AiEndpoint` whose @Prompt dispatches to a
 *      deliberately-slow runtime (e.g. a mock LLM that emits one event
 *      every 500ms).
 *   2. Has `DurableSessionInterceptor` on the chain so the
 *      `X-Atmosphere-Run-Id` header is stashed as the request attribute
 *      `org.atmosphere.session.runId`.
 *   3. Uses the default `RunRegistryHolder.get()` so the registry sees
 *      the run the endpoint registers.
 *
 * The existing default samples (personal-assistant, coding-agent) don't
 * satisfy condition 1 — their LLM dispatch is only as slow as the model
 * the operator configures. A future harness sample will plug in here;
 * until then the test documents the contract and stays green-when-skipped.
 */
const SAMPLE_URL = process.env.REATTACH_SAMPLE_URL;

test.describe('Mid-stream reattach via X-Atmosphere-Run-Id', () => {
  test.skip(
    !SAMPLE_URL,
    'set REATTACH_SAMPLE_URL to a reattach-capable sample with a deliberately-slow @AiEndpoint — '
      + 'the default personal-assistant / coding-agent samples do not gate dispatch latency '
      + 'deterministically enough to drive a disconnect-mid-stream scenario'
  );

  test.use({ baseURL: SAMPLE_URL });

  test('reconnect with X-Atmosphere-Run-Id replays buffered events', async ({ request }) => {
    // Step 1: open an initial long-poll connection, fire a prompt, and
    // read the first few events — proving the run is live and the
    // replay buffer is capturing.
    const initialResponse = await request.post('/atmosphere/agent/default', {
      data: { message: 'start a long streaming response' },
      timeout: 2_000,
      failOnStatusCode: false,
    });
    const initialBody = await initialResponse.text();

    // The sample's @Prompt dispatcher stashes the run id either in a
    // response header (REATTACH harness convention) or in a streamed
    // event envelope. Either surface is acceptable; the contract is
    // that the client can recover it.
    const runId = initialResponse.headers()['x-atmosphere-run-id']
      ?? extractRunIdFromBody(initialBody);
    expect(runId, 'sample must surface a run id the client can reconnect with').toBeTruthy();

    // Step 2: reconnect carrying the run id. The reattach hop fires
    // inside AiEndpointHandler.onReady and writes the buffered events
    // directly to this new resource (not the broadcaster — peer
    // subscribers already saw them live).
    const reattached = await request.post('/atmosphere/agent/default', {
      headers: { 'X-Atmosphere-Run-Id': runId! },
      data: { message: 'resume' },
      timeout: 5_000,
      failOnStatusCode: false,
    });
    expect(reattached.status()).toBe(200);
    const replayBody = await reattached.text();

    // Step 3: assert the replay surfaced events the initial connection
    // had either not yet seen or that the server captured while the
    // client was gone. The harness guarantees at least one "replay"
    // marker payload so this assertion can be specific instead of a
    // length-greater-than-zero hedge.
    expect(
      replayBody,
      'reattach must replay events the first connection missed — '
      + 'an empty body here means onReady.reattachPendingRun did not fire '
      + 'or RunReattachSupport.replayPendingRun returned 0'
    ).toMatch(/replay|buffered|resumed/i);
  });
});

/**
 * The reattach harness convention: sample sends a JSON envelope
 * `{"event":"run-started","runId":"…"}` before any payload events. When
 * the header isn't set, parse the id out of the body so the test still
 * works against samples that prefer in-band advertisement.
 */
function extractRunIdFromBody(body: string): string | undefined {
  // eslint-disable-next-line no-useless-escape
  const match = body.match(/"runId"\s*:\s*"([^"\\]+)"/);
  return match?.[1];
}
