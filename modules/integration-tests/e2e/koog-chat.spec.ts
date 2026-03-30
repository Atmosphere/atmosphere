import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * E2E tests for the Koog AI chat sample.
 * Verifies that the KoogAgentRuntime SPI is correctly activated
 * and the sample boots, serves the console, and accepts connections.
 */

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-koog-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test('sample starts and responds to HTTP', async () => {
  const res = await fetch(server.baseUrl);
  expect(res.status).toBeLessThan(500);
});

test('/atmosphere/console/ returns 200', async () => {
  const res = await fetch(`${server.baseUrl}/atmosphere/console/`);
  expect(res.status).toBe(200);
  const contentType = res.headers.get('content-type') ?? '';
  expect(contentType).toContain('text/html');
});

test('console loads chat layout', async ({ page }) => {
  await page.goto(`${server.baseUrl}/atmosphere/console/`);
  await expect(page.getByTestId('chat-layout')).toBeVisible();
  await expect(page.getByTestId('chat-input')).toBeVisible();
});

test('/.well-known/agent.json returns 404 (no A2A)', async () => {
  const res = await fetch(`${server.baseUrl}/.well-known/agent.json`);
  expect(res.status).toBe(404);
});
