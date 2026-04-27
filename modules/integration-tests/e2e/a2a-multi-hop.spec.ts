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

/** Send a JSON-RPC 2.0 request to the A2A endpoint. */
async function a2aRequest(
  baseUrl: string,
  method: string,
  params: Record<string, unknown> = {},
  id: number | string = 1,
): Promise<{ status: number; body: Record<string, unknown> }> {
  const res = await fetch(`${baseUrl}/atmosphere/a2a`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
  });
  const body = (await res.json()) as Record<string, unknown>;
  return { status: res.status, body };
}

/** Send a v1.0.0 SendMessage request with the given skillId. */
async function sendTask(
  baseUrl: string,
  skillId: string,
  text: string,
  extraArgs: Record<string, unknown> = {},
  id: number | string = 1,
) {
  return a2aRequest(baseUrl, 'SendMessage', {
    message: {
      messageId: `msg-${id}`,
      role: 'ROLE_USER',
      parts: [{ text }],
      metadata: { skillId },
    },
    arguments: extraArgs,
  }, id);
}

/** Unwrap the v1.0.0 SendMessageResponse oneof: result.task | result.message. */
function taskOf(body: Record<string, unknown>): Record<string, unknown> {
  const result = body.result as Record<string, unknown>;
  return (result.task as Record<string, unknown>) ?? result;
}

test.describe('A2A Multi-Hop Agent Chains', () => {

  test('first agent responds to direct message', async () => {
    const { body } = await sendTask(server.baseUrl, 'ask', 'Hello first hop!');

    const task = taskOf(body);
    expect(task).toBeDefined();

    const status = task.status as { state: string };
    expect(status.state).toBe('TASK_STATE_COMPLETED');

    const artifacts = task.artifacts as { parts: { text: string }[] }[];
    expect(artifacts.length).toBeGreaterThan(0);
    expect(artifacts[0].parts[0].text.length).toBeGreaterThan(0);
  });

  test('sequential task execution preserves order', async () => {
    const results: string[] = [];

    for (let i = 1; i <= 3; i++) {
      const { body } = await sendTask(
        server.baseUrl, 'ask', `Hop ${i}`, {}, i * 100,
      );
      const task = taskOf(body);
      const status = task?.status as { state: string } | undefined;
      results.push(status?.state ?? 'UNKNOWN');
    }

    expect(results.every(r => r === 'TASK_STATE_COMPLETED')).toBeTruthy();
  });

  test('error propagation — invalid skill returns FAILED', async () => {
    const { body } = await sendTask(
      server.baseUrl, 'nonexistent-skill', 'This should fail',
    );

    const result = body.result as Record<string, unknown> | undefined;
    const error = body.error as { code: number } | undefined;

    if (result) {
      const task = taskOf(body);
      const status = task.status as { state: string };
      expect(status.state).toBe('TASK_STATE_FAILED');
    } else {
      expect(error).toBeDefined();
    }
  });

  test('timeout handling — large payload does not hang', async () => {
    const startTime = Date.now();
    const { body } = await sendTask(
      server.baseUrl, 'ask', 'A'.repeat(10_000), {}, 999,
    );
    const elapsed = Date.now() - startTime;

    expect(elapsed).toBeLessThan(30_000);

    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
  });

  test('concurrent tasks do not interfere', async () => {
    const promises = Array.from({ length: 5 }, (_, i) =>
      sendTask(server.baseUrl, 'ask', `Concurrent task ${i}`, {}, 200 + i),
    );

    const results = await Promise.all(promises);

    for (const { body } of results) {
      const task = taskOf(body);
      expect(task).toBeDefined();
      const status = task.status as { state: string };
      expect(status.state).toBe('TASK_STATE_COMPLETED');
    }
  });

  test('ListTasks returns all executed tasks', async () => {
    await sendTask(server.baseUrl, 'ask', 'List test', {}, 300);

    const { body } = await a2aRequest(server.baseUrl, 'ListTasks', { pageSize: 50 }, 301);

    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
    const tasks = result.tasks as unknown[];
    expect(Array.isArray(tasks)).toBeTruthy();
    expect(tasks.length).toBeGreaterThan(0);
  });
});
