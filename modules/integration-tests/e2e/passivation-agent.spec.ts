import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Headline e2e for the passivation-agent sample: snapshot a paused conversation
 * to a checkpoint, inspect it, then resume and continue from where it left off.
 *
 * The proof of "resumes from where it left off" (not a cold restart) is that the
 * keyless DemoContinuationRuntime composes its resume reply FROM the restored
 * history — it quotes the last user turn. If resume had restarted the agent cold
 * (empty history), that quote would be absent and restoredHistorySize would be 0.
 * Fully deterministic, no API key.
 */

let server: SampleServer;

const PENDING = 'Please issue the refund to the customer';
const EARLIER_USER_TURN = 'My order 8675309 arrived damaged and I want a refund';

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-passivation-agent']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Passivation agent', () => {
  test('pause snapshots a checkpoint, resume continues from the restored history', async ({ request }) => {
    // 1) Pause: snapshot the paused conversation (pending action + prior turns).
    const pauseRes = await request.post(server.baseUrl + '/api/agent/pause', {
      data: {
        conversationId: 'conv-e2e-refund',
        pendingMessage: PENDING,
        reason: 'awaiting human approval',
        history: [
          { role: 'user', content: EARLIER_USER_TURN },
          { role: 'assistant', content: 'I can help with that once a human approves the refund.' },
        ],
      },
    });
    expect(pauseRes.status(), 'pause returns 200').toBe(200);
    const paused = await pauseRes.json();
    expect(paused.checkpointId, 'pause returns a checkpoint id').toBeTruthy();
    expect(paused.historySize, 'the two prior turns were captured').toBe(2);
    const checkpointId: string = paused.checkpointId;

    // 2) Inspect: the snapshot is persisted and readable without resuming.
    const inspectRes = await request.get(server.baseUrl + '/api/agent/checkpoints/' + checkpointId);
    expect(inspectRes.status(), 'checkpoint is inspectable').toBe(200);
    const snap = await inspectRes.json();
    expect(snap.runtimeName).toBe('demo-continuation');
    expect(snap.pendingMessage).toBe(PENDING);
    expect(snap.historySize).toBe(2);
    expect(JSON.stringify(snap.history)).toContain(EARLIER_USER_TURN);

    // 3) Resume: rehydrate the snapshot and continue. The reply must quote the
    // restored history — proof the agent resumed warm, not cold.
    const resumeRes = await request.post(server.baseUrl + '/api/agent/resume', {
      data: { checkpointId, signal: 'approved' },
    });
    expect(resumeRes.status(), 'resume returns 200').toBe(200);
    const resumed = await resumeRes.json();
    expect(resumed.continued, 'resume was a warm continuation').toBe(true);
    expect(resumed.restoredHistorySize, 'the full history was restored on resume').toBe(2);
    expect(resumed.response, 'the reply quotes the restored earlier turn (resumed from where it left off)')
      .toContain(EARLIER_USER_TURN);
    expect(resumed.response, 'the reply acknowledges the approval signal')
      .toContain('approved');
  });

  test('unknown checkpoint id is a 404 (pause/resume boundary handling)', async ({ request }) => {
    const inspect = await request.get(server.baseUrl + '/api/agent/checkpoints/does-not-exist');
    expect(inspect.status()).toBe(404);

    const resume = await request.post(server.baseUrl + '/api/agent/resume', {
      data: { checkpointId: 'does-not-exist', signal: 'approved' },
    });
    expect(resume.status()).toBe(404);
  });

  test('pause without a pending message is a 400', async ({ request }) => {
    const res = await request.post(server.baseUrl + '/api/agent/pause', {
      data: { conversationId: 'conv-bad', pendingMessage: '' },
    });
    expect(res.status()).toBe(400);
  });
});
