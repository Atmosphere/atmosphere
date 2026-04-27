import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-a2a-agent']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Send a JSON-RPC A2A request. */
async function a2aRequest(
  baseUrl: string,
  method: string,
  params: Record<string, unknown> = {},
  id = 1,
): Promise<Record<string, unknown>> {
  const res = await fetch(`${baseUrl}/atmosphere/a2a`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
  });
  return (await res.json()) as Record<string, unknown>;
}

/** v1.0.0 SendMessage params for skill 'ask' with the given text. */
function sendMessageParams(text: string, id: number) {
  return {
    message: {
      messageId: `msg-${id}`,
      role: 'ROLE_USER',
      parts: [{ text }],
      metadata: { skillId: 'ask' },
    },
  };
}

/** Unwrap the v1.0.0 SendMessageResponse oneof: result.task | result.message. */
function taskOf(body: Record<string, unknown>): Record<string, unknown> {
  const result = body.result as Record<string, unknown>;
  return (result.task as Record<string, unknown>) ?? result;
}

test.describe('Concurrent Protocol Access', () => {

  test('multiple concurrent A2A requests complete independently', async () => {
    const promises = Array.from({ length: 5 }, (_, i) =>
      a2aRequest(server.baseUrl, 'SendMessage',
        sendMessageParams(`Concurrent request ${i}`, i + 100), i + 100),
    );

    const results = await Promise.all(promises);

    for (const body of results) {
      const task = taskOf(body);
      expect(task).toBeDefined();
      const status = task.status as { state: string };
      expect(status.state).toBe('TASK_STATE_COMPLETED');
    }
  });

  test('agent card and task execution in parallel', async () => {
    const [cardResult, taskResult] = await Promise.all([
      a2aRequest(server.baseUrl, 'GetExtendedAgentCard', {}, 200),
      a2aRequest(server.baseUrl, 'SendMessage',
        sendMessageParams('Parallel task', 201), 201),
    ]);

    const card = cardResult.result as Record<string, unknown>;
    expect(card).toBeDefined();
    expect(card.name).toBeDefined();

    const task = taskOf(taskResult);
    expect(task).toBeDefined();
  });

  test('rapid sequential requests do not interfere', async () => {
    for (let i = 0; i < 5; i++) {
      const body = await a2aRequest(server.baseUrl, 'SendMessage',
        sendMessageParams(`Sequential ${i}`, 300 + i), 300 + i);

      const task = taskOf(body);
      expect(task).toBeDefined();
      const status = task.status as { state: string };
      expect(status.state).toBe('TASK_STATE_COMPLETED');
    }
  });
});
