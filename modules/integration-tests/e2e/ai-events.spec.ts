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

test.describe('AiEvent Wire Protocol E2E', () => {

  test('tool events: emits tool-start and tool-result', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/events');
    try {
      await client.connect();
      client.send('tools');
      await client.waitForDone(15_000);

      // Verify tool-start event
      const toolStart = client.aiEventData('tool-start');
      expect(toolStart).toBeDefined();
      expect(toolStart!.toolName).toBe('get_weather');
      expect((toolStart!.arguments as Record<string, unknown>).city).toBe('Montreal');

      // Verify tool-result event
      const toolResult = client.aiEventData('tool-result');
      expect(toolResult).toBeDefined();
      expect(toolResult!.toolName).toBe('get_weather');
      expect((toolResult!.result as Record<string, unknown>).temp).toBe(22);

      // Verify text-delta event
      const textDeltas = client.aiEvents('text-delta');
      expect(textDeltas.length).toBeGreaterThanOrEqual(1);

      // Verify complete event
      expect(client.aiEvents('complete').length).toBe(1);
    } finally {
      client.close();
    }
  });

  test('agent events: emits agent-step and progress', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/events');
    try {
      await client.connect();
      client.send('agent');
      await client.waitForDone(15_000);

      // Verify agent-step events
      const steps = client.aiEvents('agent-step');
      expect(steps.length).toBe(2);

      const step1 = steps[0].data as Record<string, unknown>;
      expect(step1.stepName).toBe('research');
      expect(step1.description).toBe('Searching for information');
      expect((step1.data as Record<string, unknown>).source).toBe('web');

      const step2 = steps[1].data as Record<string, unknown>;
      expect(step2.stepName).toBe('synthesize');

      // Verify progress event
      const progress = client.aiEventData('progress');
      expect(progress).toBeDefined();
      expect(progress!.message).toBe('Analyzing results...');
      expect(progress!.percentage).toBe(0.5);

      // Verify complete with usage metadata
      const complete = client.aiEventData('complete');
      expect(complete).toBeDefined();
      expect((complete as Record<string, unknown>).summary).toBe('Here is my analysis.');
    } finally {
      client.close();
    }
  });

  test('entity events: emits entity-start, structured-field, entity-complete', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/events');
    try {
      await client.connect();
      client.send('entity');
      await client.waitForDone(15_000);

      // Verify entity-start
      const entityStart = client.aiEventData('entity-start');
      expect(entityStart).toBeDefined();
      expect(entityStart!.typeName).toBe('UserProfile');
      expect(entityStart!.jsonSchema).toBeDefined();

      // Verify structured-field events
      const fields = client.aiEvents('structured-field');
      expect(fields.length).toBe(3);

      const nameField = fields[0].data as Record<string, unknown>;
      expect(nameField.fieldName).toBe('name');
      expect(nameField.value).toBe('Jean-François');
      expect(nameField.schemaType).toBe('string');

      const ageField = fields[1].data as Record<string, unknown>;
      expect(ageField.fieldName).toBe('age');
      expect(ageField.value).toBe(42);
      expect(ageField.schemaType).toBe('integer');

      // Verify entity-complete
      const entityComplete = client.aiEventData('entity-complete');
      expect(entityComplete).toBeDefined();
      expect(entityComplete!.typeName).toBe('UserProfile');
      const entity = entityComplete!.entity as Record<string, unknown>;
      expect(entity.name).toBe('Jean-François');
      expect(entity.age).toBe(42);
      expect(entity.city).toBe('Montreal');
    } finally {
      client.close();
    }
  });

  test('error event: emits error message string', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/events');
    try {
      await client.connect();
      client.send('error');
      await client.waitForDone(15_000);

      const errorMsg = client.messages.find(m => m.type === 'error');
      expect(errorMsg).toBeDefined();
      expect(errorMsg!.data).toBe('Rate limit exceeded');
    } finally {
      client.close();
    }
  });

  test('default prompt: emits text-delta tokens and complete', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/events');
    try {
      await client.connect();
      client.send('Hello world');
      await client.waitForDone(15_000);

      // Text deltas should contain the words
      expect(client.fullResponse).toContain('Hello');
      expect(client.fullResponse).toContain('world');
      expect(client.aiEvents('complete').length).toBe(1);
    } finally {
      client.close();
    }
  });

  test('event sequence numbers are monotonically increasing', async () => {
    const client = new AiWsClient(server.wsUrl, '/ai/events');
    try {
      await client.connect();
      client.send('tools');
      await client.waitForDone(15_000);

      const seqs = client.events
        .filter(e => e.seq != null)
        .map(e => e.seq!);
      expect(seqs.length).toBeGreaterThan(2);
      for (let i = 1; i < seqs.length; i++) {
        expect(seqs[i]).toBeGreaterThan(seqs[i - 1]);
      }
    } finally {
      client.close();
    }
  });
});
