import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket } from './helpers/transport-helper';

/** The demo auth token configured in the spring-boot-ai-chat sample fixture. */
const AUTH_TOKEN = 'demo-token';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

/**
 * Inject an init-script that patches the browser WebSocket constructor to
 * append the Atmosphere auth token as a query parameter.  The admin
 * dashboard HTML connects to /atmosphere/admin/events without any auth
 * token, so the AuthInterceptor would reject the connection.
 */
async function injectAuthToken(page: import('@playwright/test').Page) {
  await page.addInitScript((token: string) => {
    const OrigWS = window.WebSocket;
    // Patch WebSocket to inject the auth token into admin event stream connections.
    // The admin dashboard HTML connects without auth, which the AuthInterceptor rejects.
    (window as any).WebSocket = function (url: string | URL, protocols?: string | string[]) {
      const urlStr = typeof url === 'string' ? url : url.toString();
      if (urlStr.includes('/atmosphere/admin/events')) {
        const sep = urlStr.includes('?') ? '&' : '?';
        return new OrigWS(urlStr + sep + 'X-Atmosphere-Auth=' + encodeURIComponent(token), protocols);
      }
      return new OrigWS(url, protocols);
    };
    (window as any).WebSocket.CONNECTING = OrigWS.CONNECTING;
    (window as any).WebSocket.OPEN = OrigWS.OPEN;
    (window as any).WebSocket.CLOSING = OrigWS.CLOSING;
    (window as any).WebSocket.CLOSED = OrigWS.CLOSED;
    (window as any).WebSocket.prototype = OrigWS.prototype;
  }, AUTH_TOKEN);
}

test.describe('Admin REST API', () => {
  test('overview returns UP with agent and broadcaster counts', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/overview`);
    expect(res.ok()).toBeTruthy();

    const overview = await res.json();
    expect(overview.status).toBe('UP');
    expect(overview.broadcasters).toBeGreaterThan(0);
    expect(overview.agentCount).toBeGreaterThanOrEqual(0);
    expect(overview.handlers).toBeGreaterThan(0);
    expect(overview.interceptors).toBeGreaterThan(0);
    expect(overview.aiRuntime).toBeDefined();
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

    // Each broadcaster has the expected fields
    const first = broadcasters[0];
    expect(first.id).toBeDefined();
    expect(first.className).toBeDefined();
    expect(typeof first.resourceCount).toBe('number');
    expect(typeof first.isDestroyed).toBe('boolean');
  });

  test('resources endpoint returns connected resources', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/resources`);
    expect(res.ok()).toBeTruthy();

    const resources = await res.json();
    expect(Array.isArray(resources)).toBeTruthy();
    // No guaranteed connections at this point, just verify shape
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
    expect(interceptors[0].className).toBeDefined();
  });

  test('runtimes endpoint lists available AI runtimes', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/runtimes`);
    expect(res.ok()).toBeTruthy();

    const runtimes = await res.json();
    expect(runtimes.length).toBeGreaterThanOrEqual(1);

    const runtime = runtimes[0];
    expect(runtime.name).toBeDefined();
    expect(typeof runtime.priority).toBe('number');
    expect(typeof runtime.isAvailable).toBe('boolean');
    expect(Array.isArray(runtime.capabilities)).toBeTruthy();
  });

  test('runtimes/active returns the active runtime', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/runtimes/active`);
    expect(res.ok()).toBeTruthy();

    const active = await res.json();
    expect(active.name).toBeDefined();
    expect(active.isAvailable).toBe(true);
  });

  test('journal endpoint returns empty list when no coordinations', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/journal`);
    expect(res.ok()).toBeTruthy();

    const events = await res.json();
    expect(Array.isArray(events)).toBeTruthy();
  });

  test('tasks endpoint returns empty list when no A2A tasks', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/tasks`);
    expect(res.ok()).toBeTruthy();

    const tasks = await res.json();
    expect(Array.isArray(tasks)).toBeTruthy();
  });

  test('audit endpoint returns empty list initially', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/audit`);
    expect(res.ok()).toBeTruthy();

    const entries = await res.json();
    expect(Array.isArray(entries)).toBeTruthy();
  });
});

test.describe('Admin Write Operations', () => {
  test('broadcast sends a message and appears in audit log', async ({ request }) => {
    const broadcastRes = await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
      data: {
        broadcasterId: '/atmosphere/ai-chat',
        message: 'e2e test broadcast',
      },
    });
    expect(broadcastRes.ok()).toBeTruthy();

    const body = await broadcastRes.json();
    expect(body.status).toBe('broadcast sent');

    // Verify audit log recorded the action
    const auditRes = await request.get(`${server.baseUrl}/api/admin/audit`);
    const entries = await auditRes.json();
    const broadcastEntry = entries.find(
      (e: any) => e.action === 'broadcast' && e.target === '/atmosphere/ai-chat',
    );
    expect(broadcastEntry).toBeDefined();
    expect(broadcastEntry.success).toBe(true);
    expect(broadcastEntry.message).toBe('e2e test broadcast');
  });

  test('broadcast to unknown broadcaster returns 404', async ({ request }) => {
    const res = await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
      data: {
        broadcasterId: '/nonexistent/path',
        message: 'should fail',
      },
    });
    expect(res.status()).toBe(404);
  });

  test('disconnect unknown resource returns 404', async ({ request }) => {
    const res = await request.delete(
      `${server.baseUrl}/api/admin/resources/nonexistent-uuid`,
    );
    expect(res.status()).toBe(404);
  });

  test('cancel unknown task returns 404', async ({ request }) => {
    const res = await request.post(
      `${server.baseUrl}/api/admin/tasks/nonexistent-task-id/cancel`,
    );
    expect(res.status()).toBe(404);
  });
});

test.describe('Admin Dashboard UI', () => {
  test('dashboard loads with status UP and correct counters', async ({ page }) => {
    await page.goto(`${server.baseUrl}/atmosphere/admin/`);

    // Header
    await expect(page.getByText('Atmosphere Admin')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByText('Control Plane')).toBeVisible();

    // Status should be UP
    await expect(page.getByText('UP')).toBeVisible({ timeout: 10_000 });

    // Broadcasters section should have content
    await expect(page.getByText('Broadcasters').first()).toBeVisible();

    // Admin events broadcaster should always exist
    await expect(page.getByText('/atmosphere/admin/events')).toBeVisible({ timeout: 10_000 });
  });

  test('event stream indicator shows connected', async ({ page }) => {
    await injectAuthToken(page);
    await page.goto(`${server.baseUrl}/atmosphere/admin/`);
    await expect(page.getByText('Event stream: connected')).toBeVisible({ timeout: 15_000 });
  });

  test('agents tab shows AI runtimes and MCP tools sections', async ({ page }) => {
    await injectAuthToken(page);
    await page.goto(`${server.baseUrl}/atmosphere/admin/`);
    // Use the tab selector to avoid ambiguity with "Registered Agents" card header
    await page.locator('.tab', { hasText: 'Agents' }).click();

    // Should show agents section
    await expect(page.getByText('Registered Agents')).toBeVisible({ timeout: 10_000 });

    // AI Runtimes section
    await expect(page.getByText('AI Runtimes')).toBeVisible();
    await expect(page.getByText('built-in')).toBeVisible({ timeout: 5_000 });

    // MCP Tools section
    await expect(page.getByText('MCP Tools')).toBeVisible();
  });

  test('journal tab has filter controls and query button', async ({ page }) => {
    await page.goto(`${server.baseUrl}/atmosphere/admin/`);
    await page.getByText('Journal').click();

    await expect(page.getByText('Coordination Events')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByPlaceholder('Filter by coordination ID...')).toBeVisible();
    await expect(page.getByPlaceholder('Filter by agent...')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Query' })).toBeVisible();
  });

  test('control tab has broadcast, disconnect, and cancel forms', async ({ page }) => {
    await page.goto(`${server.baseUrl}/atmosphere/admin/`);
    await page.getByText('Control', { exact: true }).click();

    // Broadcast section
    await expect(page.getByText('Broadcast Message')).toBeVisible({ timeout: 10_000 });
    await expect(page.getByPlaceholder('e.g. /atmosphere/agent/myagent')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Send Broadcast' })).toBeVisible();

    // Disconnect section
    await expect(page.getByText('Disconnect Resource')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Disconnect' })).toBeVisible();

    // Cancel section
    await expect(page.getByText('Cancel A2A Task')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Cancel Task' })).toBeVisible();

    // Audit log section
    await expect(page.getByText('Audit Log')).toBeVisible();
  });

  test('broadcast from UI shows Sent confirmation and updates audit log', async ({ page }) => {
    await injectAuthToken(page);
    await page.goto(`${server.baseUrl}/atmosphere/admin/`);
    await page.getByText('Control', { exact: true }).click();

    // Fill in broadcast form
    await page.getByPlaceholder('e.g. /atmosphere/agent/myagent').fill('/atmosphere/ai-chat');
    await page.getByPlaceholder('Message to broadcast').fill('UI broadcast test');
    await page.getByRole('button', { name: 'Send Broadcast' }).click();

    // Wait for "Sent" confirmation
    await expect(page.locator('#ctrl-broadcast-result')).toContainText('Sent', { timeout: 5_000 });

    // Refresh audit log — target the refresh link inside the Audit Log card
    // (the Dashboard tab's Live Events card also has a "clear" link)
    await page.locator('#tab-control .refresh-btn').click();
    await expect(page.getByText('broadcast').last()).toBeVisible({ timeout: 5_000 });
  });
});

test.describe('Admin Event Stream', () => {
  test('WebSocket event stream delivers broadcast events', async ({ request }) => {
    // Connect to the event stream with auth token
    const conn = await connectWebSocket(server.baseUrl, '/atmosphere/admin/events', {
      headers: { 'X-Atmosphere-Auth': AUTH_TOKEN },
    });

    // Wait for connection to stabilize
    await new Promise(r => setTimeout(r, 2000));

    // Trigger a broadcast
    await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
      data: {
        broadcasterId: '/atmosphere/ai-chat',
        message: 'event stream test',
      },
    });

    // Wait for the event to arrive
    await new Promise(r => setTimeout(r, 3000));

    conn.close();

    // Should have received at least one MessageBroadcast event
    // The messages array is populated by the connectWebSocket helper
    const broadcastEvents = conn.messages.filter(m => m.includes('MessageBroadcast'));
    expect(broadcastEvents.length).toBeGreaterThanOrEqual(1);

    const event = JSON.parse(broadcastEvents[0]);
    expect(event.type).toBe('MessageBroadcast');
    expect(event.broadcasterId).toBe('/atmosphere/ai-chat');
  });
});
