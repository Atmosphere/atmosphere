/**
 * Provider-native structured output (AiCapability.NATIVE_STRUCTURED_OUTPUT)
 * end-to-end wire contract, driven through the full AiPipeline +
 * NativeStructuredDispatch seam against a deterministic runtime (no live LLM
 * key required, so this runs on CI).
 *
 * The server-side runtime (StructuredOutputTestHandler) advertises
 * NATIVE_STRUCTURED_OUTPUT, so the pipeline stamps the apply flag and wraps the
 * dispatch. Two scenarios:
 *
 *   - "ok"     — the native attempt sees the apply flag, returns schema-valid
 *                JSON, and StructuredOutputCapturingSession emits entity-complete
 *                with the parsed FilmReview. The test.native.attempt breadcrumb
 *                is `true`.
 *   - "reject" — the native attempt rejects the schema pre-stream (HTTP-400
 *                shaped). Under NativeStructuredOutputMode.AUTO the dispatch
 *                re-runs with the apply flag cleared; entity-complete STILL
 *                arrives (graceful fall-back) and the breadcrumb is `false`,
 *                proving the successful attempt was the prompt-injection path.
 */
import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8115;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Native structured output (NATIVE_STRUCTURED_OUTPUT)', () => {

  test('@smoke native-applied attempt emits entity-complete with the parsed entity', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/structured-output');
    try {
      await client.connect();
      client.send('ok');
      await client.waitForDone(15_000);

      // The provider-native attempt produced the entity (no fall-back needed).
      const attempt = await client.waitForMetadata('test.native.attempt', 5_000);
      expect(String(attempt)).toBe('true');

      // StructuredOutputCapturingSession parsed the JSON into a FilmReview and
      // emitted entity-complete with the typed entity.
      const entityComplete = client.aiEventData('entity-complete');
      expect(entityComplete, 'entity-complete event must arrive').toBeDefined();
      const entity = entityComplete!.entity as Record<string, unknown>;
      expect(entity.title).toBe('Inception');
      expect(entity.rating).toBe(9);
      expect(entity.summary).toBe('A mind-bending thriller');

      // No error reached the client on the happy path.
      expect(client.events.some(e => e.type === 'error')).toBe(false);
    } finally {
      client.close();
    }
  });

  test('@smoke AUTO falls back to prompt-injection when the provider rejects the schema', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/structured-output');
    try {
      await client.connect();
      client.send('reject');
      await client.waitForDone(15_000);

      // The pre-stream schema rejection was swallowed by the graceful-fall-back
      // guard — the client must NOT see an error frame.
      expect(client.events.some(e => e.type === 'error'),
        'a rejected native schema must not surface as a client error under AUTO').toBe(false);

      // entity-complete STILL arrives — the fall-back re-dispatch produced it.
      const entityComplete = client.aiEventData('entity-complete');
      expect(entityComplete, 'entity-complete must arrive via the fall-back path').toBeDefined();
      const entity = entityComplete!.entity as Record<string, unknown>;
      expect(entity.title).toBe('Inception');
      expect(entity.rating).toBe(9);

      // The successful attempt was the prompt-injection re-dispatch (native cleared).
      const attempt = await client.waitForMetadata('test.native.attempt', 5_000);
      expect(String(attempt)).toBe('false');
    } finally {
      client.close();
    }
  });
});
