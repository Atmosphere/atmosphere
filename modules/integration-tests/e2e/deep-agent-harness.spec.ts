import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * Deterministic (demo-mode, no real LLM) coverage for the deep-agent harness
 * primitives on a @Coordinator. Asserts REGISTRATION and RUNTIME TRUTH — the
 * things that must hold before any model turn — from the server log and the
 * console info endpoint, so the always-on Playwright lane guards them:
 *
 *   - the dynamic subagent-spawn `task` tool (deepagents parity) registers,
 *   - the classic `delegate_task` tool registers,
 *   - the planning floor (`write_todos`) registers,
 *   - and /api/console/info reports planning / filesystem / delegation ACTIVE.
 *
 * The task tool's RUNTIME spawn behavior is pinned by SpawnSubagentToolTest
 * (7 unit tests) and was chrome-devtools-proven end to end; this spec pins the
 * wiring that a real turn depends on without needing a live model.
 */
let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-personal-assistant']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Deep-agent harness on a @Coordinator', () => {
  test('registers the dynamic subagent-spawn task tool', () => {
    const output = server.getOutput();
    expect(output).toContain(
      "Harness registered task (dynamic subagent spawn) for coordinator 'primary-assistant'");
  });

  test('registers the classic delegate_task tool alongside task', () => {
    const output = server.getOutput();
    expect(output).toContain(
      "Harness registered delegate_task for coordinator 'primary-assistant'");
  });

  test('registers the planning floor (write_todos)', () => {
    const output = server.getOutput();
    expect(output).toContain('Harness registered write_todos');
  });

  test('console info reports the harness primitives as ACTIVE (runtime truth)', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/console/info`);
    expect(res.ok()).toBeTruthy();
    const info = await res.json();
    expect(info.harness, 'console info must carry a harness runtime-state block').toBeTruthy();

    // Runtime truth: the harness advertises only what it actually attached.
    expect(info.harness.planning).toContain('ACTIVE');
    expect(info.harness.filesystem).toContain('ACTIVE');
    expect(info.harness.delegation).toContain('ACTIVE');
  });
});
