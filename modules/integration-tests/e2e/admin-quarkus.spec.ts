import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

/**
 * The admin token configured in the quarkus-chat sample fixture. The
 * fixture sets ATMOSPHERE_ADMIN_HTTP_WRITE_ENABLED=true AND
 * ATMOSPHERE_ADMIN_AUTH_TOKEN=demo-token so the admin-write triple-gate
 * (feature flag → Principal → authorizer) can resolve a Principal via
 * the fourth source in the chain — X-Atmosphere-Auth validated against
 * the configured token. Mirrors the Spring demo-token posture so
 * admin-dashboard.spec.ts and admin-quarkus.spec.ts share the pattern.
 */
const AUTH_TOKEN = 'demo-token';
const AUTH_HEADER = { 'X-Atmosphere-Auth': AUTH_TOKEN };

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['quarkus-chat']);
});

/**
 * Patch window.fetch to inject X-Atmosphere-Auth on admin REST calls
 * so the browser-driven UI tests authenticate the same way as the
 * raw-request tests above. Mirrors admin-dashboard.spec.ts's helper.
 */
async function injectAuthToken(page: import('@playwright/test').Page) {
  await page.addInitScript((token: string) => {
    const origFetch = window.fetch.bind(window);
    (window as any).fetch = (input: RequestInfo | URL, init?: RequestInit) => {
      const urlStr = typeof input === 'string'
        ? input : input instanceof URL ? input.toString() : (input as Request).url;
      if (urlStr && urlStr.includes('/api/admin/')) {
        const merged: RequestInit = { ...(init ?? {}) };
        const headers = new Headers(merged.headers ?? {});
        if (!headers.has('X-Atmosphere-Auth')) {
          headers.set('X-Atmosphere-Auth', token);
        }
        merged.headers = headers;
        return origFetch(input, merged);
      }
      return origFetch(input, init);
    };
  }, AUTH_TOKEN);
}

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
  // Writes require authentication: the fixture enables writes via
  // ATMOSPHERE_ADMIN_HTTP_WRITE_ENABLED=true and sets the admin token
  // via ATMOSPHERE_ADMIN_AUTH_TOKEN, so every mutating POST / DELETE
  // must carry X-Atmosphere-Auth: demo-token to resolve a Principal.
  test('broadcast sends a message and appears in audit log', async ({ request }) => {
    const broadcastRes = await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
      headers: AUTH_HEADER,
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
      headers: AUTH_HEADER,
      data: { broadcasterId: '/nonexistent', message: 'fail' },
    });
    expect(res.status()).toBe(404);
  });

  test('disconnect unknown resource returns 404', async ({ request }) => {
    const res = await request.delete(`${server.baseUrl}/api/admin/resources/nonexistent-uuid`, {
      headers: AUTH_HEADER,
    });
    expect(res.status()).toBe(404);
  });

  test('anonymous write (no auth) returns 401', async ({ request }) => {
    // Parity with the Spring admin-dashboard.spec.ts anonymous-write
    // case: a POST that doesn't carry X-Atmosphere-Auth must be
    // rejected, not silently fall through to the feature flag check
    // or hit the audit log as success.
    const res = await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
      data: { broadcasterId: '/atmosphere/chat', message: 'should fail' },
    });
    expect(res.status()).toBe(401);
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
    // The admin UI fires /api/admin/* writes from the browser; patch
    // fetch to carry the auth token so guardWrite admits the request.
    await injectAuthToken(page);
    await page.goto(`${server.baseUrl}/admin/`);
    await page.getByText('Control', { exact: true }).click();

    await page.getByPlaceholder('e.g. /atmosphere/agent/myagent').fill('/atmosphere/chat');
    await page.getByPlaceholder('Message to broadcast').fill('Quarkus UI test');
    await page.getByRole('button', { name: 'Send Broadcast' }).click();

    await expect(page.getByText('Sent')).toBeVisible({ timeout: 5_000 });
  });
});
