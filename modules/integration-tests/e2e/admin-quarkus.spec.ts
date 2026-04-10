import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['quarkus-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Quarkus Admin REST API', () => {
  test('overview returns UP with broadcaster and handler counts', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/overview`);
    expect(res.ok()).toBeTruthy();

    const overview = await res.json();
    expect(overview.status).toBe('UP');
    expect(overview.broadcasters).toBeGreaterThan(0);
    expect(overview.handlers).toBeGreaterThan(0);
    expect(overview.interceptors).toBeGreaterThan(0);
  });

  test('agents endpoint returns agent list', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/agents`);
    expect(res.ok()).toBeTruthy();

    const agents = await res.json();
    expect(Array.isArray(agents)).toBeTruthy();
  });

  test('broadcasters endpoint lists active broadcasters', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/broadcasters`);
    expect(res.ok()).toBeTruthy();

    const broadcasters = await res.json();
    expect(broadcasters.length).toBeGreaterThan(0);

    const first = broadcasters[0];
    expect(first.id).toBeDefined();
    expect(first.className).toBeDefined();
    expect(typeof first.resourceCount).toBe('number');
  });

  test('handlers endpoint lists all handlers', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/handlers`);
    expect(res.ok()).toBeTruthy();

    const handlers = await res.json();
    expect(handlers.length).toBeGreaterThan(0);

    // Admin event handler should be registered
    const adminHandler = handlers.find((h: any) => h.path === '/atmosphere/admin/events');
    expect(adminHandler).toBeDefined();
    expect(adminHandler.className).toBe('AdminEventHandler');
  });

  test('interceptors endpoint lists interceptor chain', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/interceptors`);
    expect(res.ok()).toBeTruthy();

    const interceptors = await res.json();
    expect(interceptors.length).toBeGreaterThan(0);
  });

  test('resources endpoint returns connected resources', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/resources`);
    expect(res.ok()).toBeTruthy();
    expect(Array.isArray(await res.json())).toBeTruthy();
  });

  test('audit endpoint returns empty list initially', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/audit`);
    expect(res.ok()).toBeTruthy();
    expect(Array.isArray(await res.json())).toBeTruthy();
  });
});

test.describe('Quarkus Admin Write Operations', () => {
  test('broadcast sends a message and appears in audit log', async ({ request }) => {
    const broadcastRes = await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
      data: {
        broadcasterId: '/atmosphere/chat',
        message: 'quarkus e2e test broadcast',
      },
    });
    expect(broadcastRes.ok()).toBeTruthy();

    const body = await broadcastRes.json();
    expect(body.status).toBe('broadcast sent');

    // Verify audit log
    const auditRes = await request.get(`${server.baseUrl}/api/admin/audit`);
    const entries = await auditRes.json();
    const entry = entries.find(
      (e: any) => e.action === 'broadcast' && e.target === '/atmosphere/chat',
    );
    expect(entry).toBeDefined();
    expect(entry.success).toBe(true);
  });

  test('broadcast to unknown broadcaster returns 404', async ({ request }) => {
    const res = await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
      data: { broadcasterId: '/nonexistent', message: 'fail' },
    });
    expect(res.status()).toBe(404);
  });

  test('disconnect unknown resource returns 404', async ({ request }) => {
    const res = await request.delete(`${server.baseUrl}/api/admin/resources/nonexistent-uuid`);
    expect(res.status()).toBe(404);
  });
});

test.describe('Quarkus Admin Dashboard UI', () => {
  test('dashboard loads with status UP at /admin/', async ({ page }) => {
    await page.goto(`${server.baseUrl}/admin/`);

    await expect(page.getByText('Atmosphere Admin')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('Control Plane')).toBeVisible();
    await expect(page.getByText('UP')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('Broadcasters').first()).toBeVisible();
  });

  test('event stream indicator shows connected', async ({ page }) => {
    await page.goto(`${server.baseUrl}/admin/`);
    await expect(page.getByText('Event stream: connected')).toBeVisible({ timeout: 15_000 });
  });

  // TODO: #2598 — flaky selector for 'Agents' tab with strict mode
  test.skip('agents tab renders', async ({ page }) => {
    await page.goto(`${server.baseUrl}/admin/`);
    await page.getByText('Agents', { exact: true }).click();
    await expect(page.getByText('Registered Agents')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('AI Runtimes')).toBeVisible();
  });

  test('control tab has broadcast form and audit log', async ({ page }) => {
    await page.goto(`${server.baseUrl}/admin/`);
    await page.getByText('Control', { exact: true }).click();

    await expect(page.getByText('Broadcast Message')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByRole('button', { name: 'Send Broadcast' })).toBeVisible();
    await expect(page.getByText('Audit Log')).toBeVisible();
  });

  test('broadcast from UI shows Sent confirmation', async ({ page }) => {
    await page.goto(`${server.baseUrl}/admin/`);
    await page.getByText('Control', { exact: true }).click();

    await page.getByPlaceholder('e.g. /atmosphere/agent/myagent').fill('/atmosphere/chat');
    await page.getByPlaceholder('Message to broadcast').fill('Quarkus UI test');
    await page.getByRole('button', { name: 'Send Broadcast' }).click();

    await expect(page.getByText('Sent')).toBeVisible({ timeout: 5_000 });
  });
});
