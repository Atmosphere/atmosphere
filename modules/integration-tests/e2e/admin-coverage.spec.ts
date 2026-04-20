import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor, retryAsync } from './helpers/transport-helper';

/**
 * Extended admin coverage — complements admin-dashboard.spec.ts.
 *
 * Tests agent detail, broadcaster detail & lifecycle, resource lifecycle,
 * unicast, audit filtering, input validation, and event-stream resource events.
 *
 * Uses the same spring-boot-ai-chat sample (has admin + ai, no MCP/coordinator/metrics).
 */

const AUTH_TOKEN = 'demo-token';
// Header bundle every mutating REST request must carry. Admin writes now
// enforce authentication — anonymous callers receive 401 before reaching
// the ControlAuthorizer. Tests reuse the sample's demo token which the
// AuthInterceptor resolves into a "demo-user" Principal.
const AUTH = { 'X-Atmosphere-Auth': AUTH_TOKEN };

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-ai-chat']);
});

test.afterAll(async () => {
  await server?.stop();
});

// ── Agent Detail ──

test.describe('Admin REST — Agent Detail', () => {
  test('get agent by name returns detail for known agent', async ({ request }) => {
    // First discover available agents
    const listRes = await request.get(`${server.baseUrl}/api/admin/agents`);
    expect(listRes.ok()).toBeTruthy();
    const agents = await listRes.json();

    if (agents.length === 0) {
      test.skip(true, 'No agents registered in this sample');
      return;
    }

    const agentName = agents[0].name;
    const detailRes = await request.get(`${server.baseUrl}/api/admin/agents/${agentName}`);
    expect(detailRes.ok()).toBeTruthy();

    const detail = await detailRes.json();
    expect(detail.name).toBe(agentName);
    expect(detail.path).toBeDefined();
  });

  test('get unknown agent returns 404', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/agents/nonexistent-agent-xyz`);
    expect(res.status()).toBe(404);
  });

  test('list sessions for known agent returns array', async ({ request }) => {
    const listRes = await request.get(`${server.baseUrl}/api/admin/agents`);
    const agents = await listRes.json();

    if (agents.length === 0) {
      test.skip(true, 'No agents registered in this sample');
      return;
    }

    const agentName = agents[0].name;
    const sessionsRes = await request.get(
      `${server.baseUrl}/api/admin/agents/${agentName}/sessions`,
    );
    expect(sessionsRes.ok()).toBeTruthy();
    expect(Array.isArray(await sessionsRes.json())).toBeTruthy();
  });
});

// ── Broadcaster Detail ──

test.describe('Admin REST — Broadcaster Detail', () => {
  test('get broadcaster detail by id returns enriched info', async ({ request }) => {
    const listRes = await request.get(`${server.baseUrl}/api/admin/broadcasters`);
    expect(listRes.ok()).toBeTruthy();
    const broadcasters = await listRes.json();
    expect(broadcasters.length).toBeGreaterThan(0);

    const broadcasterId = broadcasters[0].id;
    const detailRes = await request.get(
      `${server.baseUrl}/api/admin/broadcasters/detail?id=${encodeURIComponent(broadcasterId)}`,
    );
    expect(detailRes.ok()).toBeTruthy();

    const detail = await detailRes.json();
    expect(detail.id).toBe(broadcasterId);
    expect(detail.className).toBeDefined();
    expect(typeof detail.resourceCount).toBe('number');
  });

  test('get detail for unknown broadcaster returns 404', async ({ request }) => {
    const res = await request.get(
      `${server.baseUrl}/api/admin/broadcasters/detail?id=/nonexistent/path`,
    );
    expect(res.status()).toBe(404);
  });
});

// ── Resource Lifecycle ──

test.describe('Admin REST — Resource Lifecycle', () => {
  test('connect → list → disconnect → verify gone', async ({ request }) => {
    // Snapshot resources before connecting
    const beforeRes = await request.get(`${server.baseUrl}/api/admin/resources`);
    const beforeUuids = new Set(
      (await beforeRes.json()).map((r: any) => r.uuid),
    );

    // Connect a WebSocket client
    const conn = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat', {
      headers: { 'X-Atmosphere-Auth': AUTH_TOKEN },
    });
    // Let the connection register
    await new Promise(r => setTimeout(r, 2000));

    // Find the new resource by diffing
    const afterRes = await request.get(`${server.baseUrl}/api/admin/resources`);
    const afterResources: any[] = await afterRes.json();
    const newResource = afterResources.find((r: any) => !beforeUuids.has(r.uuid));
    expect(newResource).toBeDefined();

    const uuid = newResource.uuid;
    expect(newResource.transport).toBeDefined();

    // Disconnect via admin API
    const disconnectRes = await request.delete(
        `${server.baseUrl}/api/admin/resources/${uuid}`, { headers: AUTH });
    expect(disconnectRes.ok()).toBeTruthy();
    const body = await disconnectRes.json();
    expect(body.status).toBe('resource disconnected');

    // Verify resource is gone
    await retryAsync(async () => {
      const checkRes = await request.get(`${server.baseUrl}/api/admin/resources`);
      const remaining: any[] = await checkRes.json();
      if (remaining.some((r: any) => r.uuid === uuid)) {
        throw new Error('Resource still present');
      }
    }, { maxRetries: 10, baseDelayMs: 500, label: 'wait for resource removal' });

    conn.close();
  });

  test('resume unknown resource returns 404', async ({ request }) => {
    const res = await request.post(
      `${server.baseUrl}/api/admin/resources/nonexistent-uuid/resume`,
      { headers: AUTH },
    );
    expect(res.status()).toBe(404);
  });
});

// ── Unicast ──

test.describe('Admin REST — Unicast', () => {
  test('unicast delivers message to specific resource', async ({ request }) => {
    // Snapshot before
    const beforeRes = await request.get(`${server.baseUrl}/api/admin/resources`);
    const beforeUuids = new Set(
      (await beforeRes.json()).map((r: any) => r.uuid),
    );

    // Connect a WS client
    const conn = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat', {
      headers: { 'X-Atmosphere-Auth': AUTH_TOKEN },
    });
    await new Promise(r => setTimeout(r, 2000));

    // Find our resource UUID
    const afterRes = await request.get(`${server.baseUrl}/api/admin/resources`);
    const afterResources: any[] = await afterRes.json();
    const newResource = afterResources.find((r: any) => !beforeUuids.has(r.uuid));
    expect(newResource).toBeDefined();

    // Snapshot messages arrived so far (the initial connect may yield a
    // UUID handshake frame). The unicast-induced frames come AFTER this
    // marker.
    const before = conn.messages.length;

    // Unicast a message
    const unicastRes = await request.post(`${server.baseUrl}/api/admin/broadcasters/unicast`, {
      headers: AUTH,
      data: {
        broadcasterId: '/atmosphere/ai-chat',
        uuid: newResource.uuid,
        message: 'unicast-e2e-test',
      },
    });
    expect(unicastRes.ok()).toBeTruthy();
    const body = await unicastRes.json();
    expect(body.status).toBe('unicast sent');

    // AiEndpointHandler interprets a plain-String broadcaster message as a
    // user prompt (see the "Plain String = user prompt" branch in
    // AiEndpointHandler.onStateChange). Admin unicast therefore manifests
    // on the wire as a prompt-driven response stream, not as a literal
    // echo of the bytes. Proof of delivery: at least one new frame arrives
    // on the target resource after the unicast POST returned 200.
    await waitFor(() => conn.messages.length > before, 10_000);
    expect(conn.messages.length).toBeGreaterThan(before);

    // Verify audit log recorded the unicast
    const auditRes = await request.get(`${server.baseUrl}/api/admin/audit`);
    const entries = await auditRes.json();
    const unicastEntry = entries.find((e: any) => e.action === 'unicast');
    expect(unicastEntry).toBeDefined();
    expect(unicastEntry.success).toBe(true);

    conn.close();
  });

  test('unicast to unknown resource returns 404', async ({ request }) => {
    const res = await request.post(`${server.baseUrl}/api/admin/broadcasters/unicast`, {
      headers: AUTH,
      data: {
        broadcasterId: '/atmosphere/ai-chat',
        uuid: 'nonexistent-uuid',
        message: 'should fail',
      },
    });
    expect(res.status()).toBe(404);
  });
});

// ── Input Validation ──

test.describe('Admin REST — Input Validation', () => {
  test('broadcast with missing broadcasterId returns 400', async ({ request }) => {
    const res = await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
      headers: AUTH,
      data: { message: 'no broadcaster id' },
    });
    expect(res.status()).toBe(400);
    const body = await res.json();
    expect(body.error).toContain('missing');
  });

  test('broadcast with missing message returns 400', async ({ request }) => {
    const res = await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
      headers: AUTH,
      data: { broadcasterId: '/atmosphere/ai-chat' },
    });
    expect(res.status()).toBe(400);
    const body = await res.json();
    expect(body.error).toContain('missing');
  });

  test('unicast with missing fields returns 400', async ({ request }) => {
    const res = await request.post(`${server.baseUrl}/api/admin/broadcasters/unicast`, {
      headers: AUTH,
      data: { broadcasterId: '/atmosphere/ai-chat', message: 'no uuid' },
    });
    expect(res.status()).toBe(400);
    const body = await res.json();
    expect(body.error).toContain('missing');
  });
});

// ── Audit Log Filtering ──

test.describe('Admin REST — Audit Log Filtering', () => {
  test('audit limit parameter restricts result count', async ({ request }) => {
    // Perform multiple broadcasts to accumulate audit entries
    for (let i = 0; i < 3; i++) {
      await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
        headers: AUTH,
        data: {
          broadcasterId: '/atmosphere/ai-chat',
          message: `audit-limit-test-${i}`,
        },
      });
    }

    // Fetch with limit=1
    const limitedRes = await request.get(`${server.baseUrl}/api/admin/audit?limit=1`);
    expect(limitedRes.ok()).toBeTruthy();
    const limited = await limitedRes.json();
    expect(limited.length).toBe(1);

    // Fetch without limit — should have more
    const allRes = await request.get(`${server.baseUrl}/api/admin/audit`);
    expect(allRes.ok()).toBeTruthy();
    const all = await allRes.json();
    expect(all.length).toBeGreaterThan(1);
  });

  test('audit entries are ordered chronologically (oldest first)', async ({ request }) => {
    // ControlAuditLog stores entries in a ConcurrentLinkedDeque with
    // addLast; entries() / entries(limit) return "most recent last" per
    // its javadoc. Timestamps therefore monotonically non-decrease across
    // the returned list.
    const res = await request.get(`${server.baseUrl}/api/admin/audit`);
    const entries = await res.json();

    if (entries.length < 2) return; // not enough data to check ordering

    for (let i = 1; i < entries.length; i++) {
      expect(new Date(entries[i].timestamp).getTime())
        .toBeGreaterThanOrEqual(new Date(entries[i - 1].timestamp).getTime());
    }
  });
});

// ── Broadcaster Destroy (destructive — runs last) ──

test.describe('Admin REST — Broadcaster Destroy', () => {
  test('destroy broadcaster removes it from listing', async ({ request }) => {
    // First, trigger a broadcast to ensure the target broadcaster exists
    await request.post(`${server.baseUrl}/api/admin/broadcasters/broadcast`, {
      headers: AUTH,
      data: {
        broadcasterId: '/atmosphere/ai-chat',
        message: 'pre-destroy check',
      },
    });

    // Verify it exists
    const beforeRes = await request.get(`${server.baseUrl}/api/admin/broadcasters`);
    const before: any[] = await beforeRes.json();
    const exists = before.some((b: any) => b.id === '/atmosphere/ai-chat');
    expect(exists).toBeTruthy();

    // Destroy it
    const destroyRes = await request.delete(
      `${server.baseUrl}/api/admin/broadcasters/destroy?id=${encodeURIComponent('/atmosphere/ai-chat')}`,
      { headers: AUTH },
    );
    expect(destroyRes.ok()).toBeTruthy();
    const body = await destroyRes.json();
    expect(body.status).toBe('broadcaster destroyed');

    // Verify it's gone (or marked destroyed)
    const afterRes = await request.get(`${server.baseUrl}/api/admin/broadcasters`);
    const after: any[] = await afterRes.json();
    const remaining = after.find((b: any) => b.id === '/atmosphere/ai-chat');
    // Broadcaster is either removed or marked destroyed
    if (remaining) {
      expect(remaining.isDestroyed).toBe(true);
    }

    // Audit should record the destruction
    const auditRes = await request.get(`${server.baseUrl}/api/admin/audit`);
    const entries = await auditRes.json();
    const destroyEntry = entries.find(
      (e: any) => e.action === 'destroy_broadcaster' && e.target === '/atmosphere/ai-chat',
    );
    expect(destroyEntry).toBeDefined();
    expect(destroyEntry.success).toBe(true);
  });

  test('destroy unknown broadcaster returns 404', async ({ request }) => {
    const res = await request.delete(
      `${server.baseUrl}/api/admin/broadcasters/destroy?id=/nonexistent/broadcaster`,
      { headers: AUTH },
    );
    expect(res.status()).toBe(404);
  });
});

// ── Optional Endpoints (graceful handling when dependencies absent) ──

test.describe('Admin REST — Optional Controllers', () => {
  test('coordinators returns empty list when no coordinator module', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/coordinators`);
    expect(res.ok()).toBeTruthy();
    const coordinators = await res.json();
    expect(Array.isArray(coordinators)).toBeTruthy();
  });

  test('journal returns empty list when no coordinator module', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/journal`);
    expect(res.ok()).toBeTruthy();
    const events = await res.json();
    expect(Array.isArray(events)).toBeTruthy();
  });

  test('tasks returns empty list when no A2A module', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/tasks`);
    expect(res.ok()).toBeTruthy();
    const tasks = await res.json();
    expect(Array.isArray(tasks)).toBeTruthy();
  });

  test('mcp/tools returns empty list when no MCP module', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/mcp/tools`);
    expect(res.ok()).toBeTruthy();
    const tools = await res.json();
    expect(Array.isArray(tools)).toBeTruthy();
  });

  test('mcp/resources returns empty list when no MCP module', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/mcp/resources`);
    expect(res.ok()).toBeTruthy();
    expect(Array.isArray(await res.json())).toBeTruthy();
  });

  test('mcp/prompts returns empty list when no MCP module', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/mcp/prompts`);
    expect(res.ok()).toBeTruthy();
    expect(Array.isArray(await res.json())).toBeTruthy();
  });

  test('metrics returns graceful response when no Micrometer', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/metrics`);
    expect(res.ok()).toBeTruthy();
    // Either a real snapshot or an error message — both are valid
    const body = await res.json();
    expect(body).toBeDefined();
  });

  test('metrics/all returns empty list or meters when no Micrometer', async ({ request }) => {
    const res = await request.get(`${server.baseUrl}/api/admin/metrics/all`);
    expect(res.ok()).toBeTruthy();
    expect(Array.isArray(await res.json())).toBeTruthy();
  });
});

// ── Event Stream — Resource Events ──

test.describe('Admin Event Stream — Resource Events', () => {
  test('event stream receives ResourceConnected on client connect', async () => {
    // Connect to admin event stream
    const adminConn = await connectWebSocket(server.baseUrl, '/atmosphere/admin/events', {
      headers: { 'X-Atmosphere-Auth': AUTH_TOKEN },
    });

    // Let the admin stream stabilize
    await new Promise(r => setTimeout(r, 2000));

    // Connect a chat client — this should trigger ResourceConnected
    const chatConn = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat', {
      headers: { 'X-Atmosphere-Auth': AUTH_TOKEN },
    });

    // Wait for the ResourceConnected event
    await waitFor(
      () => adminConn.messages.some(m => m.includes('ResourceConnected')),
      15_000,
    );

    const connectEvent = adminConn.messages.find(m => m.includes('ResourceConnected'));
    expect(connectEvent).toBeDefined();

    const parsed = JSON.parse(connectEvent!);
    expect(parsed.type).toBe('ResourceConnected');
    expect(parsed.uuid).toBeDefined();
    expect(parsed.transport).toBeDefined();
    expect(parsed.timestamp).toBeDefined();

    chatConn.close();
    adminConn.close();
  });

  test('event stream receives ResourceDisconnected on client close', async () => {
    // Connect admin stream
    const adminConn = await connectWebSocket(server.baseUrl, '/atmosphere/admin/events', {
      headers: { 'X-Atmosphere-Auth': AUTH_TOKEN },
    });
    await new Promise(r => setTimeout(r, 2000));

    // Connect and then disconnect a chat client
    const chatConn = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat', {
      headers: { 'X-Atmosphere-Auth': AUTH_TOKEN },
    });
    await new Promise(r => setTimeout(r, 1000));

    // Clear admin messages to only watch for disconnect
    const msgCountBefore = adminConn.messages.length;
    chatConn.close();

    // Wait for ResourceDisconnected event (checking new messages only)
    await waitFor(
      () => adminConn.messages.slice(msgCountBefore).some(m => m.includes('ResourceDisconnected')),
      15_000,
    );

    const disconnectEvent = adminConn.messages
      .slice(msgCountBefore)
      .find(m => m.includes('ResourceDisconnected'));
    expect(disconnectEvent).toBeDefined();

    const parsed = JSON.parse(disconnectEvent!);
    expect(parsed.type).toBe('ResourceDisconnected');
    expect(parsed.uuid).toBeDefined();
    expect(parsed.timestamp).toBeDefined();

    adminConn.close();
  });
});
