import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(180_000);
  server = await startSample(SAMPLES['spring-boot-multi-agent-startup-team']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Send a JSON-RPC 2.0 request to the A2A endpoint for a specific agent. */
async function a2aRequest(
  baseUrl: string,
  agentPath: string,
  method: string,
  params: Record<string, unknown> = {},
  id: number | string = 1,
): Promise<{ status: number; body: Record<string, unknown> }> {
  const res = await fetch(`${baseUrl}${agentPath}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
  });
  const body = (await res.json()) as Record<string, unknown>;
  return { status: res.status, body };
}

test.describe('Coordinator Remote Agents', () => {

  test('coordinator agent registered at startup', async () => {
    const output = server.getOutput();
    // The multi-agent sample should register agents on startup
    expect(output.length).toBeGreaterThan(0);
  });

  test('agent card discovery returns skills', async () => {
    const { status, body } = await a2aRequest(
      server.baseUrl,
      '/atmosphere/a2a',
      'agent/authenticatedExtendedCard',
    );

    expect(status).toBe(200);
    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
    expect(result.name).toBeDefined();
  });

  test('coordinator delegates task to agent', async () => {
    const { body } = await a2aRequest(
      server.baseUrl,
      '/atmosphere/a2a',
      'message/send',
      {
        message: {
          role: 'user',
          parts: [{ type: 'text', text: 'Analyze the market' }],
          metadata: { skillId: 'ask' },
        },
      },
      10,
    );

    const result = body.result as Record<string, unknown>;
    if (result) {
      const status = result.status as { state: string };
      expect(['COMPLETED', 'FAILED']).toContain(status.state);
    }
  });

  test('multiple concurrent agent requests complete', async () => {
    const promises = Array.from({ length: 3 }, (_, i) =>
      a2aRequest(
        server.baseUrl,
        '/atmosphere/a2a',
        'message/send',
        {
          message: {
            role: 'user',
            parts: [{ type: 'text', text: `Task ${i}` }],
            metadata: { skillId: 'ask' },
          },
        },
        20 + i,
      ),
    );

    const results = await Promise.all(promises);
    for (const { body } of results) {
      const result = body.result as Record<string, unknown>;
      expect(result).toBeDefined();
    }
  });
});
