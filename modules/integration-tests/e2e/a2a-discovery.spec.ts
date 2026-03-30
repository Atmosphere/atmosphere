import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Verifies the /.well-known/agent.json A2A discovery endpoint.
 *
 * Covers Gist 2, Fix 2:
 * - Samples WITHOUT A2A should return 404 (filter delegates to chain)
 * - spring-boot-a2a-agent returns 200 with a single agent card
 * - spring-boot-multi-agent-startup-team returns 200 with 5 agent cards
 */

// Samples WITHOUT A2A dependency — should return 404
const NON_A2A_SAMPLES = [
  'spring-boot-chat',
  'spring-boot-mcp-server',
] as const;

for (const sampleName of NON_A2A_SAMPLES) {
  test.describe(`A2A Discovery (no A2A) — ${sampleName}`, () => {
    let server: SampleServer;

    test.beforeAll(async () => {
      test.setTimeout(120_000);
      server = await startSample(SAMPLES[sampleName]);
    });

    test.afterAll(async () => {
      await server?.stop();
    });

    test(`/.well-known/agent.json returns 404`, async () => {
      const res = await fetch(`${server.baseUrl}/.well-known/agent.json`);
      expect(res.status).toBe(404);
    });
  });
}

// spring-boot-a2a-agent — single agent card
test.describe('A2A Discovery — spring-boot-a2a-agent', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-a2a-agent']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('returns 200 with single agent card', async () => {
    const res = await fetch(`${server.baseUrl}/.well-known/agent.json`);
    expect(res.status).toBe(200);

    const body = await res.json();
    // Single card: not wrapped in array
    expect(body).toHaveProperty('name');
    expect(body.name).toBe('atmosphere-assistant');
  });

  test('agent card has expected skills', async () => {
    const res = await fetch(`${server.baseUrl}/.well-known/agent.json`);
    const body = await res.json();

    expect(body.skills).toBeDefined();
    expect(body.skills.length).toBe(3);

    const skillIds = body.skills.map((s: { id: string }) => s.id);
    expect(skillIds).toContain('ask');
    expect(skillIds).toContain('get-weather');
    expect(skillIds).toContain('get-time');
  });

  test('content-type is application/json', async () => {
    const res = await fetch(`${server.baseUrl}/.well-known/agent.json`);
    const contentType = res.headers.get('content-type') ?? '';
    expect(contentType).toContain('application/json');
  });
});

// spring-boot-multi-agent-startup-team — multiple agent cards
test.describe('A2A Discovery — spring-boot-multi-agent-startup-team', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-multi-agent-startup-team']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('returns 200 with array of 5 agent cards', async () => {
    const res = await fetch(`${server.baseUrl}/.well-known/agent.json`);
    expect(res.status).toBe(200);

    const body = await res.json();
    expect(Array.isArray(body)).toBeTruthy();
    expect(body.length).toBe(5);
  });

  test('all cards have required fields', async () => {
    const res = await fetch(`${server.baseUrl}/.well-known/agent.json`);
    const cards = await res.json();

    for (const card of cards) {
      expect(card.name).toBeTruthy();
      expect(card.url).toBeTruthy();
      expect(card.skills).toBeDefined();
    }
  });

  test('contains expected agent names', async () => {
    const res = await fetch(`${server.baseUrl}/.well-known/agent.json`);
    const cards = await res.json();
    const names = cards.map((c: { name: string }) => c.name);

    expect(names).toContain('writer-agent');
    expect(names).toContain('research-agent');
    expect(names).toContain('strategy-agent');
    expect(names).toContain('finance-agent');
  });
});
