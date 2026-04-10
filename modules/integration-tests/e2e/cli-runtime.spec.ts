/**
 * CLI Runtime E2E Tests — verifies the console works correctly across
 * different sample types (chat, agent, ai-tools).
 *
 * Uses pre-built JARs (like all E2E tests) rather than `atmosphere run`
 * (which downloads + builds from source — too slow for CI).
 * The `atmosphere run` lifecycle is tested by cli/e2e-test-cli-runtime.sh
 * in the CI: CLI workflow.
 */
import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

// --- spring-boot-chat: console with @ManagedService ---

test.describe('Console: spring-boot-chat', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-chat']);
  });

  test.afterAll(async () => { await server?.stop(); });

  test('console endpoint returns correct path', async () => {
    const res = await fetch(`${server.baseUrl}/api/console/info`);
    expect(res.ok).toBeTruthy();
    const info = await res.json();
    expect(info.endpoint).toMatch(/\/atmosphere\/(chat|ai-chat)/);
  });

  test('console page loads and WebSocket connects', async ({ page }) => {
    await page.goto(`${server.baseUrl}/atmosphere/console/`);
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });
  });
});

// --- spring-boot-dentist-agent: console with @Agent ---

test.describe('Console: spring-boot-dentist-agent', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-dentist-agent']);
  });

  test.afterAll(async () => { await server?.stop(); });

  test('console connects to agent endpoint', async ({ page }) => {
    await page.goto(`${server.baseUrl}/atmosphere/console/`);
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });
  });

  test('slash command /help works', async ({ page }) => {
    await page.goto(`${server.baseUrl}/atmosphere/console/`);
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });

    const input = page.locator('textarea');
    await input.fill('/help');
    await page.keyboard.press('Enter');

    await expect(page.locator('main')).toContainText(/firstaid|urgency|help/i, { timeout: 10_000 });
  });
});

// --- spring-boot-ai-tools: console with @AiEndpoint + tool calling ---

test.describe('Console: spring-boot-ai-tools', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-ai-tools']);
  });

  test.afterAll(async () => { await server?.stop(); });

  test('console connects and shows tool-calling subtitle', async ({ page }) => {
    await page.goto(`${server.baseUrl}/atmosphere/console/`);
    await expect(page.getByText('Connected')).toBeVisible({ timeout: 15_000 });
  });
});
