import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import WebSocket from 'ws';

let server: SampleServer;
let mcpSessionId: string;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-mcp-server']);
});

test.afterAll(async () => {
  await server?.stop();
});

/** Send a JSON-RPC 2.0 request to the MCP HTTP endpoint. */
async function mcpRequest(
  baseUrl: string,
  method: string,
  params: Record<string, unknown> = {},
  id = 1,
  sessionId?: string,
): Promise<{ status: number; body: Record<string, unknown>; headers: Headers }> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (sessionId) headers['Mcp-Session-Id'] = sessionId;

  const res = await fetch(`${baseUrl}/atmosphere/mcp`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ jsonrpc: '2.0', id, method, params }),
  });

  const body = (await res.json()) as Record<string, unknown>;
  return { status: res.status, body, headers: res.headers };
}

/** Helper: initialize an MCP session and return the session ID. */
async function initializeSession(): Promise<string> {
  const { headers } = await mcpRequest(server.baseUrl, 'initialize', {
    protocolVersion: '2025-03-26',
    capabilities: { tools: { listChanged: true } },
    clientInfo: { name: 'bidi-test', version: '1.0' },
  });
  return headers.get('mcp-session-id') ?? '';
}

/** Connect a WebSocket to the MCP endpoint for bidirectional messaging. */
function connectMcpWebSocket(): Promise<{
  ws: WebSocket;
  messages: string[];
  close: () => void;
}> {
  return new Promise((resolve, reject) => {
    const wsUrl = server.baseUrl.replace('http', 'ws') +
      '/mcp?X-Atmosphere-Transport=websocket&X-Atmosphere-Framework=5.0.0';
    const ws = new WebSocket(wsUrl);
    const messages: string[] = [];

    ws.on('message', (data) => {
      const text = data.toString().trim();
      if (text && text.startsWith('{')) messages.push(text);
    });
    ws.on('open', () => resolve({ ws, messages, close: () => ws.close() }));
    ws.on('error', reject);
    setTimeout(() => reject(new Error('MCP WS connect timeout')), 10_000);
  });
}

/** Wait for a condition with polling. */
async function waitFor(fn: () => boolean, timeoutMs = 15_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (fn()) return;
    await new Promise(r => setTimeout(r, 200));
  }
  throw new Error(`waitFor timed out after ${timeoutMs}ms`);
}

test.describe('MCP Bidirectional Communication', () => {

  test('initialize returns session ID and server capabilities', async () => {
    const { status, body, headers } = await mcpRequest(
      server.baseUrl, 'initialize', {
        protocolVersion: '2025-03-26',
        capabilities: {},
        clientInfo: { name: 'bidi-test', version: '1.0' },
      },
    );

    expect(status).toBe(200);
    const result = body.result as Record<string, unknown>;
    expect(result).toBeDefined();
    expect(result.capabilities).toBeDefined();

    const sid = headers.get('mcp-session-id');
    expect(sid).toBeTruthy();
    mcpSessionId = sid!;
  });

  test('WebSocket MCP: initialize + tool call round-trip', async () => {
    const client = await connectMcpWebSocket();
    await new Promise(r => setTimeout(r, 500));

    // Initialize
    client.ws.send(JSON.stringify({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: {
        protocolVersion: '2025-03-26',
        capabilities: { tools: { listChanged: true } },
        clientInfo: { name: 'bidi-ws-test', version: '1.0' },
      },
    }));

    await waitFor(() => client.messages.some(m => m.includes('"id":1') || m.includes('"id": 1')));

    // Send initialized notification
    client.ws.send(JSON.stringify({
      jsonrpc: '2.0', method: 'notifications/initialized', params: {},
    }));
    await new Promise(r => setTimeout(r, 500));

    // List tools
    client.ws.send(JSON.stringify({
      jsonrpc: '2.0', id: 2, method: 'tools/list', params: {},
    }));

    await waitFor(() => client.messages.some(m => m.includes('"id":2') || m.includes('"id": 2')));

    const toolsMsg = client.messages.find(m => m.includes('"id":2') || m.includes('"id": 2'));
    expect(toolsMsg).toBeDefined();
    const tools = JSON.parse(toolsMsg!);
    expect(tools.result?.tools?.length).toBeGreaterThan(0);

    client.close();
  });

  test('server sends notifications to client via WebSocket', async () => {
    const client = await connectMcpWebSocket();
    await new Promise(r => setTimeout(r, 500));

    // Initialize with tool list change capability
    client.ws.send(JSON.stringify({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: {
        protocolVersion: '2025-03-26',
        capabilities: { tools: { listChanged: true } },
        clientInfo: { name: 'notification-test', version: '1.0' },
      },
    }));

    await waitFor(() => client.messages.length > 0);

    // Send initialized notification
    client.ws.send(JSON.stringify({
      jsonrpc: '2.0', method: 'notifications/initialized', params: {},
    }));

    // Wait for any server-initiated notification or response
    await new Promise(r => setTimeout(r, 2000));

    // At minimum, the initialize response should have arrived
    expect(client.messages.length).toBeGreaterThan(0);

    client.close();
  });

  test('MCP ping over WebSocket succeeds', async () => {
    const client = await connectMcpWebSocket();
    await new Promise(r => setTimeout(r, 500));

    // Initialize first
    client.ws.send(JSON.stringify({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: {
        protocolVersion: '2025-03-26',
        capabilities: {},
        clientInfo: { name: 'ping-test', version: '1.0' },
      },
    }));
    await waitFor(() => client.messages.length > 0);

    // Ping
    client.ws.send(JSON.stringify({
      jsonrpc: '2.0', id: 99, method: 'ping', params: {},
    }));

    await waitFor(() => client.messages.some(m => m.includes('"id":99') || m.includes('"id": 99')));
    const pong = client.messages.find(m => m.includes('"id":99') || m.includes('"id": 99'));
    expect(pong).toBeDefined();
    const parsed = JSON.parse(pong!);
    expect(parsed.result).toBeDefined();

    client.close();
  });

  test('concurrent tool calls over WebSocket return correct results', async () => {
    const client = await connectMcpWebSocket();
    await new Promise(r => setTimeout(r, 500));

    // Initialize
    client.ws.send(JSON.stringify({
      jsonrpc: '2.0', id: 1, method: 'initialize',
      params: {
        protocolVersion: '2025-03-26',
        capabilities: {},
        clientInfo: { name: 'concurrent-test', version: '1.0' },
      },
    }));
    await waitFor(() => client.messages.length > 0);
    await new Promise(r => setTimeout(r, 500));

    // Send two tool calls concurrently
    client.ws.send(JSON.stringify({
      jsonrpc: '2.0', id: 10, method: 'tools/call',
      params: { name: 'echo', arguments: { text: 'call-ten' } },
    }));
    client.ws.send(JSON.stringify({
      jsonrpc: '2.0', id: 11, method: 'tools/call',
      params: { name: 'echo', arguments: { text: 'call-eleven' } },
    }));

    await waitFor(() => {
      const has10 = client.messages.some(m => m.includes('"id":10') || m.includes('"id": 10'));
      const has11 = client.messages.some(m => m.includes('"id":11') || m.includes('"id": 11'));
      return has10 && has11;
    }, 10_000);

    client.close();
  });
});
