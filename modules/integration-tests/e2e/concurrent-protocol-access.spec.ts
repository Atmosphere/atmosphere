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

test.describe('Concurrent Protocol Access', () => {

  test('multiple concurrent A2A requests complete independently', async () => {
    const promises = Array.from({ length: 5 }, (_, i) =>
      a2aRequest(server.baseUrl, 'message/send', {
        message: {
          role: 'user',
          parts: [{ type: 'text', text: `Concurrent request ${i}` }],
          metadata: { skillId: 'ask' },
        },
      }, i + 100),
    );

    const results = await Promise.all(promises);

    for (const body of results) {
      const result = body.result as Record<string, unknown>;
      expect(result).toBeDefined();
      const status = result.status as { state: string };
      expect(status.state).toBe('COMPLETED');
    }
  });

  test('agent card and task execution in parallel', async () => {
    const [cardResult, taskResult] = await Promise.all([
      a2aRequest(server.baseUrl, 'agent/authenticatedExtendedCard', {}, 200),
      a2aRequest(server.baseUrl, 'message/send', {
        message: {
          role: 'user',
          parts: [{ type: 'text', text: 'Parallel task' }],
          metadata: { skillId: 'ask' },
        },
      }, 201),
    ]);

    // Card should return agent info
    const card = cardResult.result as Record<string, unknown>;
    expect(card).toBeDefined();
    expect(card.name).toBeDefined();

    // Task should complete
    const task = taskResult.result as Record<string, unknown>;
    expect(task).toBeDefined();
  });

  test('rapid sequential requests do not interfere', async () => {
    for (let i = 0; i < 5; i++) {
      const body = await a2aRequest(server.baseUrl, 'message/send', {
        message: {
          role: 'user',
          parts: [{ type: 'text', text: `Sequential ${i}` }],
          metadata: { skillId: 'ask' },
        },
      }, 300 + i);

      const result = body.result as Record<string, unknown>;
      expect(result).toBeDefined();
      const status = result.status as { state: string };
      expect(status.state).toBe('COMPLETED');
    }
  });
});
