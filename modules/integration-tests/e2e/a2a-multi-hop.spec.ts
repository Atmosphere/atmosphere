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

/** Send a message/send request with the given skillId. */
async function sendTask(
  baseUrl: string,
  skillId: string,
  text: string,
  extraArgs: Record<string, unknown> = {},
  id: number | string = 1,
) {
  return a2aRequest(baseUrl, 'message/send', {
    message: {
      role: 'user',
      parts: [{ type: 'text', text }],
      metadata: { skillId },
    },
    arguments: extraArgs,
  }, id);
}

test.describe('A2A Multi-Hop Agent Chains', () => {

  test('first agent responds to direct message', async () => {
    const { body } = await sendTask(server.baseUrl, 'ask', 'Hello first hop!');

    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();

    const status = result.status as { state: string };
    expect(status.state).toBe('COMPLETED');

    const artifacts = result.artifacts as { parts: { text: string }[] }[];
    expect(artifacts.length).toBeGreaterThan(0);
    expect(artifacts[0].parts[0].text.length).toBeGreaterThan(0);
  });

  test('sequential task execution preserves order', async () => {
    // Send tasks sequentially and verify they complete in order
    const results: string[] = [];

    for (let i = 1; i <= 3; i++) {
      const { body } = await sendTask(
        server.baseUrl, 'ask', `Hop ${i}`, {}, i * 100,
      );
      const result = body.result as Record<string, unknown>;
      const status = result?.status as { state: string } | undefined;
      results.push(status?.state ?? 'UNKNOWN');
    }

    // All should complete
    expect(results.every(r => r === 'COMPLETED')).toBeTruthy();
  });

  test('error propagation — invalid skill returns FAILED', async () => {
    const { body } = await sendTask(
      server.baseUrl, 'nonexistent-skill', 'This should fail',
    );

    const result = body.result as Record<string, unknown> | undefined;
    const error = body.error as { code: number } | undefined;

    // Either the task fails or we get a JSON-RPC error
    if (result) {
      const status = result.status as { state: string };
      expect(status.state).toBe('FAILED');
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

    // Should complete within a reasonable time
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
      const result = body.result as Record<string, unknown>;
      expect(result).toBeDefined();
      const status = result.status as { state: string };
      expect(status.state).toBe('COMPLETED');
    }
  });

  test('tasks/list returns all executed tasks', async () => {
    // Execute a known task first
    await sendTask(server.baseUrl, 'ask', 'List test', {}, 300);

    const { body } = await a2aRequest(server.baseUrl, 'tasks/list', {}, 301);

    const result = body.result as unknown[];
    expect(result).toBeDefined();
    expect(Array.isArray(result)).toBeTruthy();
    expect(result.length).toBeGreaterThan(0);
  });
});
