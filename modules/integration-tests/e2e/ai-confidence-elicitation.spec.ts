import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8113;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * E2E coverage for the framework-level AiConfidenceElicitation path —
 * verifies the ConfidenceCapturingSession decorator parses the
 * model-emitted confidence field on stream completion and fires
 * StreamingSession.confidence(...) ahead of the terminal frame, so the
 * default sink emits ai.confidence.aggregate / .source / .tokens
 * metadata on the wire. Pairs with AiPipelineConfidenceTest (unit).
 */
test.describe('AI Confidence Elicitation E2E', () => {

  test('@smoke model-reported confidence parses and emits aggregate metadata', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/confidence-elicitation');
    try {
      await client.connect();
      client.send('reported');
      await client.waitForDone(15_000);

      // The runtime emitted text containing {"confidence": 0.83}; the
      // decorator parsed it and the session's default confidence sink
      // emitted ai.confidence.aggregate as a metadata frame.
      expect(client.metadata.get('ai.confidence.aggregate')).toBe(0.83);
      expect(client.metadata.get('ai.confidence.source')).toBe('MODEL_REPORTED_FIELD');
    } finally {
      client.close();
    }
  });

  test('missing confidence field surfaces source without aggregate', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/confidence-elicitation');
    try {
      await client.connect();
      client.send('missing');
      await client.waitForDone(15_000);

      // Unknown surfaces source-only — lets routers distinguish "model
      // did not comply" from "elicitation was never installed."
      expect(client.metadata.has('ai.confidence.aggregate')).toBe(false);
      expect(client.metadata.get('ai.confidence.source')).toBe('MODEL_REPORTED_FIELD');
    } finally {
      client.close();
    }
  });

  test('out-of-range confidence value falls back to unknown', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/confidence-elicitation');
    try {
      await client.connect();
      client.send('out-of-range');
      await client.waitForDone(15_000);

      expect(client.metadata.has('ai.confidence.aggregate')).toBe(false);
      expect(client.metadata.get('ai.confidence.source')).toBe('MODEL_REPORTED_FIELD');
    } finally {
      client.close();
    }
  });

  test('elicitation disabled means no confidence metadata reaches the wire', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/confidence-elicitation');
    try {
      await client.connect();
      client.send('disabled');
      await client.waitForDone(15_000);

      expect(client.metadata.has('ai.confidence.aggregate')).toBe(false);
      expect(client.metadata.has('ai.confidence.source')).toBe(false);
    } finally {
      client.close();
    }
  });

  test('per-request elicitation overrides pipeline default field name', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/confidence-elicitation');
    try {
      await client.connect();
      // Pipeline default looks for "confidence"; per-request override
      // looks for "score" instead. Runtime returns text with "score": 0.42.
      client.send('per-request:score');
      await client.waitForDone(15_000);

      expect(client.metadata.get('ai.confidence.aggregate')).toBe(0.42);
      expect(client.metadata.get('ai.confidence.source')).toBe('MODEL_REPORTED_FIELD');
    } finally {
      client.close();
    }
  });

  for (const [prompt, route, aggregate] of [
    ['route:high', 'ACT', 0.95],
    ['route:mid', 'CONFIRM', 0.83],
    ['route:none', 'ESCALATE', undefined],
  ] as const) {
    test(`confidence routing emits ${route} for ${prompt}`, async () => {
      const client = new AiWsClient(server.wsUrl, '/ai/confidence-elicitation');
      try {
        await client.connect();
        // The pipeline has a ConfidenceRouting and no elicitation: the
        // default cue is installed for it, the field is parsed, and the
        // route rides the wire ahead of the terminal frame. No field at
        // all fails closed to ESCALATE.
        client.send(prompt);
        await client.waitForDone(15_000);

        expect(client.metadata.get('ai.confidence.route')).toBe(route);
        if (aggregate === undefined) {
          expect(client.metadata.has('ai.confidence.aggregate')).toBe(false);
        } else {
          expect(client.metadata.get('ai.confidence.aggregate')).toBe(aggregate);
        }
      } finally {
        client.close();
      }
    });
  }
});
