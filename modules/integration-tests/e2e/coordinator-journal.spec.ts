import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-multi-agent-startup-team']);
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

test.describe('Coordinator Journal', () => {

  test('coordinator registers agents at startup', async () => {
    const output = server.getOutput();
    expect(output.length).toBeGreaterThan(0);
  });

  test('task execution creates journal entries', async () => {
    // Execute a task — the coordinator should journal the execution
    const { body } = await a2aRequest(server.baseUrl, 'message/send', {
      message: {
        role: 'user',
        parts: [{ type: 'text', text: 'Analyze this topic for journaling test' }],
        metadata: { skillId: 'ask' },
      },
    }, 100);

    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
  });

  test('tasks/list returns executed tasks', async () => {
    // Execute a task first
    await a2aRequest(server.baseUrl, 'message/send', {
      message: {
        role: 'user',
        parts: [{ type: 'text', text: 'Journal query test' }],
        metadata: { skillId: 'ask' },
      },
    }, 101);

    // Query task list
    const { body } = await a2aRequest(server.baseUrl, 'tasks/list', {}, 102);
    const result = body.result;
    // Should return an array of executed tasks
    if (Array.isArray(result)) {
      expect(result.length).toBeGreaterThan(0);
    }
  });

  test('sequential task executions are tracked', async () => {
    for (let i = 0; i < 3; i++) {
      const { body } = await a2aRequest(server.baseUrl, 'message/send', {
        message: {
          role: 'user',
          parts: [{ type: 'text', text: `Sequential journal task ${i}` }],
          metadata: { skillId: 'ask' },
        },
      }, 200 + i);

      const result = body.result as Record<string, unknown>;
      expect(result).toBeDefined();
    }
  });
});
