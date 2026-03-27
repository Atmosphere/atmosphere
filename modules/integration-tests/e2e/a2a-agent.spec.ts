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

/** Send a message/send request with the given skillId and extra arguments. */
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

test.describe('A2A Agent Protocol', () => {
  test('agent card discovery returns 3 skills', async () => {
    const { status, body } = await a2aRequest(
      server.baseUrl,
      'agent/authenticatedExtendedCard',
    );

    expect(status).toBe(200);
    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
    expect(result.name).toBe('atmosphere-assistant');

    const skills = result.skills as { id: string }[];
    expect(skills).toHaveLength(3);

    const skillIds = skills.map(s => s.id);
    expect(skillIds).toContain('ask');
    expect(skillIds).toContain('get-weather');
    expect(skillIds).toContain('get-time');
  });

  test('ask skill returns a response', async () => {
    const { body } = await sendTask(server.baseUrl, 'ask', 'Hello!');

    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();

    const status = result.status as { state: string };
    expect(status.state).toBe('COMPLETED');

    const artifacts = result.artifacts as { parts: { text: string }[] }[];
    expect(artifacts.length).toBeGreaterThan(0);
    expect(artifacts[0].parts[0].text.length).toBeGreaterThan(0);
  });

  test('get-weather returns weather info', async () => {
    const { body } = await sendTask(
      server.baseUrl,
      'get-weather',
      'Montreal weather',
      { location: 'Montreal' },
    );

    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();

    const status = result.status as { state: string };
    expect(status.state).toBe('COMPLETED');

    const artifacts = result.artifacts as { parts: { text: string }[] }[];
    const text = artifacts[0].parts[0].text;
    expect(text.length).toBeGreaterThan(0);
  });

  test('get-time returns time for valid timezone', async () => {
    const { body } = await sendTask(
      server.baseUrl,
      'get-time',
      'Time in New York',
      { timezone: 'America/New_York' },
    );

    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();

    const status = result.status as { state: string };
    expect(status.state).toBe('COMPLETED');

    const artifacts = result.artifacts as { parts: { text: string }[] }[];
    const text = artifacts[0].parts[0].text;
    expect(text).toContain('America/New_York');
    // Format: yyyy-MM-dd HH:mm:ss z
    expect(text).toMatch(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
  });

  test('get-time with invalid timezone returns FAILED', async () => {
    const { body } = await sendTask(
      server.baseUrl,
      'get-time',
      'Time in invalid zone',
      { timezone: 'Invalid/Zone' },
    );

    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();

    const status = result.status as { state: string };
    expect(status.state).toBe('FAILED');
  });

  test('tasks/list returns executed tasks', async () => {
    // Send two tasks
    await sendTask(server.baseUrl, 'ask', 'First question', {}, 10);
    await sendTask(server.baseUrl, 'ask', 'Second question', {}, 11);

    const { body } = await a2aRequest(server.baseUrl, 'tasks/list', {}, 12);

    const result = body.result as unknown[];
    expect(result).toBeDefined();
    expect(Array.isArray(result)).toBeTruthy();
    expect(result.length).toBeGreaterThanOrEqual(2);
  });

  test('missing method returns INVALID_REQUEST (-32600)', async () => {
    const res = await fetch(`${server.baseUrl}/atmosphere/a2a`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ jsonrpc: '2.0', id: 99 }),
    });
    const body = (await res.json()) as Record<string, unknown>;

    const error = body.error as { code: number; message: string };
    expect(error).toBeDefined();
    expect(error.code).toBe(-32600);
  });

  test('unknown method returns METHOD_NOT_FOUND (-32601)', async () => {
    const { body } = await a2aRequest(server.baseUrl, 'nonexistent', {}, 100);

    const error = body.error as { code: number; message: string };
    expect(error).toBeDefined();
    expect(error.code).toBe(-32601);
  });

  test('invalid JSON body returns parse error (-32700)', async () => {
    const res = await fetch(`${server.baseUrl}/atmosphere/a2a`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{{not valid json!!',
    });
    const body = (await res.json()) as Record<string, unknown>;

    const error = body.error as { code: number; message: string };
    expect(error).toBeDefined();
    expect(error.code).toBe(-32700);
  });

  test('agent card has expected skill metadata', async () => {
    const { body } = await a2aRequest(
      server.baseUrl,
      'agent/authenticatedExtendedCard',
    );

    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();

    const skills = result.skills as { id: string; name: string; description: string }[];
    expect(skills).toBeDefined();
    expect(skills.length).toBeGreaterThan(0);

    for (const skill of skills) {
      expect(skill.id).toBeTruthy();
      expect(skill.name).toBeTruthy();
      expect(skill.description).toBeTruthy();
    }
  });
});
