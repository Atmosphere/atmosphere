import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { ChatPage } from './helpers/chat-page';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Chat Observability & Actuator', () => {
  test('actuator health endpoint returns UP', async ({ request }) => {
    // Actuator may take a moment to initialize after the server is ready
    let res;
    for (let i = 0; i < 10; i++) {
      res = await request.get(`${server.baseUrl}/actuator/health`);
      if (res.ok()) break;
      await new Promise(r => setTimeout(r, 1000));
    }
    expect(res!.ok()).toBeTruthy();

    const health = await res!.json();
    expect(health.status).toBe('UP');
  });

  test('actuator metrics index lists atmosphere metrics', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/actuator/metrics`);
    expect(res.ok()).toBeTruthy();

    const metrics = await res.json();
    expect(metrics.names).toBeDefined();
    expect(Array.isArray(metrics.names)).toBeTruthy();

    // After server startup, atmosphere.* metrics should be registered
    const atmosphereMetrics = metrics.names.filter(
      (n: string) => n.startsWith('atmosphere.'),
    );
    expect(atmosphereMetrics.length).toBeGreaterThan(0);
  });

  test('atmosphere.connections.total metric exists and is queryable', async ({ request }) => {
    const res = await request.get(
      `${server.baseUrl}/actuator/metrics/atmosphere.connections.total`,
    );
    expect(res.ok()).toBeTruthy();

    const metric = await res.json();
    expect(metric.name).toBe('atmosphere.connections.total');
    expect(metric.measurements).toBeDefined();
    expect(metric.measurements.length).toBeGreaterThan(0);
  });

  test('observability tab loads health and metrics', async ({ page }) => {
    // First, connect as a user to generate some metrics
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();
    await chat.joinAs('MetricsUser');
    await chat.sendMessage('hello for metrics');

    // Switch to Observability tab
    await page.getByText('Observability').click();

    // Health status should show "UP"
    await expect(page.getByText('Health Status')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('UP').first()).toBeVisible({ timeout: 10_000 });
  });

  test('observability tab shows atmosphere metrics after activity', async ({ page }) => {
    // Generate activity first
    const chat = new ChatPage(page);
    await chat.goto(server.baseUrl);
    await chat.waitForConnected();
    await chat.joinAs('MetricsViewer');
    await chat.sendMessage('metric trigger');

    // Wait a moment for metrics to be recorded
    await page.waitForTimeout(2_000);

    // Switch to Observability tab
    await page.getByText('Observability').click();

    // Should show Atmosphere Metrics heading
    await expect(page.getByText('Atmosphere Metrics')).toBeVisible({ timeout: 10_000 });

    // At least one atmosphere metric should be visible
    await expect(page.getByText('atmosphere.', { exact: false }).first())
      .toBeVisible({ timeout: 10_000 });
  });
});
