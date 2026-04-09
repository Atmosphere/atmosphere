import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

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

  test('health endpoint returns UP after client activity', async ({ page, request }) => {
    // First, connect a client via the console to generate some activity
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 15_000 });

    await page.getByTestId('chat-input').fill('hello for metrics');
    await page.getByTestId('chat-send').click();

    // Verify health endpoint still reports UP after activity
    const res = await request.get(`${server.baseUrl}/actuator/health`);
    expect(res.ok()).toBeTruthy();

    const health = await res.json();
    expect(health.status).toBe('UP');
  });

  test('atmosphere metrics reflect activity after console usage', async ({ page, request }) => {
    // Generate activity via the console
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await expect(page.getByTestId('status-label')).toHaveText('Connected', { timeout: 15_000 });

    await page.getByTestId('chat-input').fill('metric trigger');
    await page.getByTestId('chat-send').click();

    // Wait a moment for metrics to be recorded
    await page.waitForTimeout(2_000);

    // Query the metrics index and verify atmosphere metrics are present
    const metricsRes = await request.get(`${server.baseUrl}/actuator/metrics`);
    expect(metricsRes.ok()).toBeTruthy();

    const metrics = await metricsRes.json();
    const atmosphereMetrics = metrics.names.filter(
      (n: string) => n.startsWith('atmosphere.'),
    );
    expect(atmosphereMetrics.length).toBeGreaterThan(0);

    // Query at least one atmosphere metric directly to verify it has measurements
    const metricName = atmosphereMetrics[0];
    const detailRes = await request.get(`${server.baseUrl}/actuator/metrics/${metricName}`);
    expect(detailRes.ok()).toBeTruthy();

    const detail = await detailRes.json();
    expect(detail.name).toBe(metricName);
    expect(detail.measurements).toBeDefined();
    expect(detail.measurements.length).toBeGreaterThan(0);
  });
});
