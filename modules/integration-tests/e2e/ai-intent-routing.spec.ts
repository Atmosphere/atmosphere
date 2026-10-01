import { test, expect } from '@playwright/test';
import { startAiTestServer, type AiTestServer } from './fixtures/ai-test-server';
import { AiWsClient } from './helpers/ai-ws-client';

const PORT = 8122;
let server: AiTestServer;

test.beforeAll(async () => {
  server = await startAiTestServer(PORT);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * E2E coverage for intent routing (IntentRoutingTestHandler). Every scenario runs
 * on both dispatch paths — the AiStreamingSession the @AiEndpoint handler
 * dispatches through, and AiPipeline — with one IntentRouting whose classifier is
 * the real RuntimeDecisionModel over a scripted runtime that reports a
 * DECISION_LOGPROBS distribution over the route codes.
 *
 * Routes: track (deterministic handler), general (the LLM path, an echo runtime),
 * agent (the human route). Tiers: ACT >= 0.9, CONFIRM >= 0.5, else ESCALATE.
 */
const MODES = ['endpoint', 'pipeline'] as const;

async function turn(prompt: string): Promise<AiWsClient> {
  const client = new AiWsClient(server.wsUrl, '/ai/intent');
  await client.connect();
  client.sendResilient(prompt);
  return client;
}

async function waitForApproval(client: AiWsClient, timeoutMs = 15_000): Promise<Record<string, unknown>> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const data = client.aiEventData('approval-required');
    if (data) return data;
    await new Promise(r => setTimeout(r, 100));
  }
  throw new Error(`no approval-required frame within ${timeoutMs}ms: ${JSON.stringify(client.events)}`);
}

test.describe('AI Intent Routing E2E', () => {
  for (const mode of MODES) {

    test(`@smoke ${mode}: a confident deterministic choice answers without the LLM`, async () => {
      const client = await turn(`${mode}:where is order 7?`);
      try {
        await client.waitForDone(15_000);
        expect(client.errors).toEqual([]);
        expect(client.fullResponse).toBe('Order 7 is out for delivery');
        expect(client.fullResponse).not.toContain('llm:');
        expect(client.metadata.get('ai.intent.route')).toBe('track');
        expect(client.metadata.get('ai.intent.choice')).toBe('track');
        expect(client.metadata.get('ai.intent.tier')).toBe('ACT');
        // Margin of the chosen code over three routes: (3 * 0.97 - 1) / 2.
        expect(client.metadata.get('ai.intent.confidence') as number).toBeCloseTo(0.955, 3);
      } finally {
        client.close();
      }
    });

    test(`${mode}: an LLM choice continues to the runtime`, async () => {
      const client = await turn(`${mode}:tell me a joke`);
      try {
        await client.waitForDone(15_000);
        expect(client.errors).toEqual([]);
        expect(client.fullResponse).toBe('llm: tell me a joke');
        expect(client.metadata.get('ai.intent.route')).toBe('general');
        expect(client.metadata.get('ai.intent.tier')).toBe('ACT');
      } finally {
        client.close();
      }
    });

    test(`${mode}: a low-confidence choice escalates to the human route`, async () => {
      const client = await turn(`${mode}:something vague about my account`);
      try {
        await client.waitForDone(15_000);
        expect(client.fullResponse).toMatch(/^A person will follow up \(ticket T-\d+\)$/);
        expect(client.metadata.get('ai.intent.route')).toBe('agent');
        expect(client.metadata.get('ai.intent.choice')).toBe('track');
        expect(client.metadata.get('ai.intent.tier')).toBe('ESCALATE');
      } finally {
        client.close();
      }
    });

    test(`${mode}: an unparseable classification fails closed to the human route`, async () => {
      const client = await turn(`${mode}:garbled order text`);
      try {
        await client.waitForDone(15_000);
        expect(client.metadata.get('ai.intent.route')).toBe('agent');
        expect(client.metadata.get('ai.intent.tier')).toBe('ESCALATE');
        expect(client.metadata.has('ai.intent.choice')).toBe(false);
        expect(client.metadata.has('ai.intent.confidence')).toBe(false);
        expect(client.fullResponse).not.toContain('Order 7');
      } finally {
        client.close();
      }
    });

    test(`${mode}: a CONFIRM-tier choice asks the requester and acts on approval`, async () => {
      const client = await turn(`${mode}:my parcel?`);
      try {
        const approval = await waitForApproval(client);
        expect(approval.toolName).toBe('intent:track');
        expect((approval.arguments as Record<string, unknown>).route).toBe('track');
        expect(client.fullResponse).toBe('');
        client.send(`/__approval/${approval.approvalId}/approve`);
        await client.waitForDone(15_000);
        expect(client.fullResponse).toBe('Order 7 is out for delivery');
        expect(client.metadata.get('ai.intent.route')).toBe('track');
        expect(client.metadata.get('ai.intent.tier')).toBe('CONFIRM');
      } finally {
        client.close();
      }
    });

    test(`${mode}: a denied confirmation escalates`, async () => {
      const client = await turn(`${mode}:my parcel?`);
      try {
        const approval = await waitForApproval(client);
        client.send(`/__approval/${approval.approvalId}/deny`);
        await client.waitForDone(15_000);
        expect(client.fullResponse).toMatch(/^A person will follow up/);
        expect(client.metadata.get('ai.intent.route')).toBe('agent');
        expect(client.metadata.get('ai.intent.tier')).toBe('CONFIRM');
      } finally {
        client.close();
      }
    });
  }
});
