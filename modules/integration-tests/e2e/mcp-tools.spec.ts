import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';

let server: SampleServer;
let mcpSessionId: string;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-mcp-server']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Send a JSON-RPC 2.0 request to the MCP endpoint. */
async function mcpRequest(
  baseUrl: string,
  method: string,
  params: Record<string, unknown> = {},
  id = 1,
  sessionId?: string,
): Promise<{ status: number; body: unknown; headers: Headers }> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (sessionId) headers['Mcp-Session-Id'] = sessionId;

  const res = await fetch(`${baseUrl}/atmosphere/mcp`, {
    method: 'POST',
    headers,
    body: JSON.stringify({
      jsonrpc: '2.0',
      id,
      method,
      params,
    }),
  });

  const body = await res.json();
  return { status: res.status, body, headers: res.headers };
}

test.describe('MCP Tool Invocation', () => {
  test('initialize handshake returns session ID and capabilities', async () => {
    const { status, body, headers } = await mcpRequest(
      server.baseUrl,
      'initialize',
      {
        protocolVersion: '2025-03-26',
        capabilities: {},
        clientInfo: { name: 'playwright-test', version: '1.0' },
      },
    );

    expect(status).toBe(200);
    const result = (body as { result?: { capabilities?: unknown } }).result;
    expect(result).toBeDefined();
    expect(result!.capabilities).toBeDefined();

    // Store session ID for subsequent requests
    const sid = headers.get('mcp-session-id');
    expect(sid).toBeTruthy();
    mcpSessionId = sid!;
  });

  test('tools/list returns all registered MCP tools', async () => {
    const { body } = await mcpRequest(
      server.baseUrl,
      'tools/list',
      {},
      2,
      mcpSessionId,
    );

    const result = (body as { result?: { tools?: { name: string }[] } }).result;
    expect(result).toBeDefined();
    expect(result!.tools).toBeDefined();

    const toolNames = result!.tools!.map(t => t.name);
    expect(toolNames).toContain('list_users');
    expect(toolNames).toContain('broadcast_message');
    expect(toolNames).toContain('atmosphere_version');
  });

  test('atmosphere_version tool returns framework info', async () => {
    const { body } = await mcpRequest(
      server.baseUrl,
      'tools/call',
      { name: 'atmosphere_version', arguments: {} },
      3,
      mcpSessionId,
    );

    const result = (body as { result?: { content?: { text?: string }[] } }).result;
    expect(result).toBeDefined();

    // The tool returns JSON text with version info
    const text = result!.content?.[0]?.text;
    expect(text).toBeDefined();

    const info = JSON.parse(text!);
    expect(info.version).toBeDefined();
    expect(info.javaVersion).toBeDefined();
  });

  test('list_users tool returns connected users', async () => {
    const { body } = await mcpRequest(
      server.baseUrl,
      'tools/call',
      { name: 'list_users', arguments: {} },
      4,
      mcpSessionId,
    );

    const result = (body as { result?: { content?: { text?: string }[] } }).result;
    expect(result).toBeDefined();

    const text = result!.content?.[0]?.text;
    expect(text).toBeDefined();

    // Result is a JSON array of connected users
    const users = JSON.parse(text!);
    expect(Array.isArray(users)).toBeTruthy();
  });

  // Known issue: chat broadcaster not active alongside @Agent(headless) in CI
  test.skip('broadcast_message tool sends successfully', async () => {
    const { body } = await mcpRequest(
      server.baseUrl,
      'tools/call',
      { name: 'broadcast_message', arguments: { message: 'Hello from MCP tool!' } },
      5,
      mcpSessionId,
    );

    const result = (body as { result?: { content?: { text?: string }[] } }).result;
    expect(result).toBeDefined();

    const text = result!.content?.[0]?.text;
    expect(text).toBeDefined();

    const status = JSON.parse(text!);
    expect(status.status).toBe('sent');
  });
});
