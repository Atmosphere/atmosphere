import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { WebSocket } from 'ws';

/*
 * Both runtime variants of spring-boot-personal-assistant are packaged BEFORE the
 * run — the default (Built-in) jar in target/, the -Pruntime-langchain4j jar in
 * target/runtime-langchain4j (the e2e workflow's build job packages both). The
 * hooks only boot them.
 *
 * This spec used to run `./mvnw install` from inside its beforeAll hooks. On a CI
 * runner that build outlived Playwright's hook timeout, the half-started server
 * kept port 8100, and every retry failed with "Port 8100 is already in use" — so
 * the spec was kept out of every workflow and the outbound-MCP path had no CI
 * validation. Locally:
 *   ./mvnw package -pl samples/spring-boot-mcp-server,samples/spring-boot-personal-assistant -am -DskipTests
 *   ./mvnw package -pl samples/spring-boot-personal-assistant -am -Pruntime-langchain4j -DskipTests
 */

/**
 * Outbound MCP — proves the spring-boot-personal-assistant sample's
 * UpstreamMcpAgent endpoint discovers tools advertised by a remote MCP
 * server (spring-boot-mcp-server, port 8083) at startup, and exposes them
 * to the agent loop via the AgentExecutionContext.tools() pipeline.
 *
 * All but one test run without an LLM API key — they target the MCP-client
 * connection lifecycle, the advertised tool inventory, the @AiEndpoint
 * registration and the Console, none of which needs a model round-trip. The
 * one test that proves a model-driven tool dispatch through McpToolSource
 * skips unless LLM_API_KEY (or GEMINI_API_KEY) is set; no CI lane sets one
 * for this spec, so that dispatch is validated only locally.
 *
 * Two describe-blocks run the same scenario against two AgentRuntime
 * implementations to validate the cross-runtime SPI claim:
 * <ul>
 *   <li>Default build: Built-in runtime (priority 0)</li>
 *   <li>{@code -Pruntime-langchain4j}: LangChain4j runtime (priority 100)</li>
 * </ul>
 * Same sample code, same prompt path, same assertions — only the Maven
 * profile differs. This proves the architectural claim that outbound
 * {@code ToolDefinition}s flow through {@code AgentExecutionContext.tools()}
 * regardless of which runtime executes the agent loop.
 */
const COMMON_TOOL_COUNT_REGEX = /Connected to MCP server http:\/\/localhost:8083\S* — \d+ tool\(s\) advertised/;

let upstream: SampleServer;
let agent: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(240_000);
  // Upstream MUST be running before the agent starts — the agent's
  // RemoteToolsConfig connects in @PostConstruct.
  upstream = await startSample(SAMPLES['spring-boot-mcp-server']);
});

test.afterAll(async () => {
  await upstream?.stop();
});

test.describe('Outbound MCP — Built-in runtime', () => {

  test.beforeAll(async () => {
    agent = await startSample(SAMPLES['spring-boot-personal-assistant']);
  });

  test.afterAll(async () => {
    await agent?.stop();
  });

  test('@smoke agent connects to upstream MCP server at startup', async () => {
    const log = agent.getOutput();
    expect(log).toMatch(COMMON_TOOL_COUNT_REGEX);
    const match = log.match(/Connected to MCP server http:\/\/localhost:8083\S* — (\d+) tool\(s\) advertised/);
    expect(match).not.toBeNull();
    const count = parseInt(match![1], 10);
    expect(count).toBeGreaterThanOrEqual(1);
  });

  test('@smoke agent endpoint accepts WebSocket connections', async () => {
    const wsUrl = agent.baseUrl.replace('http://', 'ws://') + '/atmosphere/personal-assistant/upstream-tools';
    const ws = new WebSocket(wsUrl);
    await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('connect timed out')), 10_000);
      ws.on('open', () => { clearTimeout(timer); resolve(); });
      ws.on('error', (err) => { clearTimeout(timer); reject(err); });
    });
    expect(ws.readyState).toBe(1);
    ws.close();
  });

  test('console UI loads (chrome-devtools layer) and reports built-in runtime', async ({ page }) => {
    const response = await page.goto(`${agent.baseUrl}/atmosphere/console/`);
    expect(response?.status()).toBe(200);
    await expect(page).toHaveTitle(/Atmosphere/i);
    // Without any framework adapter on the classpath, the AgentRuntime
    // resolver picks the Built-in runtime (priority 0). The Console badge
    // surfaces the resolved name verbatim — drift here means a runtime
    // adapter snuck into the default classpath. With no API key (CI), the
    // Built-in runtime has no model to reach and the resolver reports the
    // DemoAgentRuntime fallback instead, so the badge reads "demo".
    const expected = process.env.LLM_API_KEY || process.env.GEMINI_API_KEY
      ? /Runtime:\s*built-in/i
      : /Runtime:\s*demo/i;
    await expect(page.getByText(expected)).toBeVisible({ timeout: 15_000 });
  });

  test('admin endpoint surfaces remote tool inventory + metrics', async () => {
    const response = await fetch(`${agent.baseUrl}/api/mcp-client/sources`);
    expect(response.ok).toBe(true);
    const body = await response.json() as {
      connected: boolean;
      endpoint: string;
      toolCount: number;
      tools: Array<{ name: string; calls: number; errors: number; lastLatencyMs: number; avgLatencyMs: number }>;
    };
    expect(body.connected).toBe(true);
    expect(body.endpoint).toContain('http://localhost:8083');
    expect(body.toolCount).toBeGreaterThanOrEqual(1);
    expect(body.tools.length).toBe(body.toolCount);
    // Each tool row carries the metric shape — proves the wire surface for
    // operator dashboards is stable.
    const sample = body.tools[0];
    expect(sample).toHaveProperty('calls');
    expect(sample).toHaveProperty('errors');
    expect(sample).toHaveProperty('lastLatencyMs');
    expect(sample).toHaveProperty('avgLatencyMs');
  });

  // Console tab matrix — covers every nav button I drove manually via
  // chrome-devtools while validating this feature. Each click navigates
  // within the SPA (no page reload); the assertion targets text the
  // matching admin sub-controller surfaces, not a static skeleton, so a
  // wired-up backend is required for the test to pass. The SPA itself is
  // the same WebJar across runtimes, so testing once under the Built-in
  // describe is sufficient — runtime swap doesn't change the UI assets.
  for (const [tab, marker] of [
    ['Chat', /Type a message/i],
    ['Sessions', /Active sessions/i],
    ['Policies', /Installed policies|No governance policies/i],
    ['Decisions', /Recent decisions|ADMIT/i],
    ['Commitments', /Commitment records|0 SIGNED/i],
    ['OWASP', /OWASP Agentic AI Top 10/i],
  ] as Array<[string, RegExp]>) {
    test(`console "${tab}" tab loads and renders backend-sourced content`, async ({ page }) => {
      await page.goto(`${agent.baseUrl}/atmosphere/console/`);
      await expect(page).toHaveTitle(/Atmosphere/i);
      if (tab !== 'Chat') {
        // Click the nav button to switch tabs (Chat is the default landing).
        await page.getByRole('button', { name: new RegExp(`^${tab}`, 'i') }).first().click();
      }
      await expect(page.getByText(marker).first()).toBeVisible({ timeout: 15_000 });
    });
  }
});

test.describe('Outbound MCP — LangChain4j runtime (cross-runtime parity)', () => {

  test.beforeAll(async () => {
    agent = await startSample(SAMPLES['spring-boot-personal-assistant-langchain4j']);
  });

  test.afterAll(async () => {
    await agent?.stop();
  });

  test('@smoke same upstream connection succeeds under LangChain4j runtime', async () => {
    const log = agent.getOutput();
    expect(log).toMatch(COMMON_TOOL_COUNT_REGEX);
  });

  test('console reports langchain4j runtime — proving SPI swap', async ({ page }) => {
    // The architectural claim under test: swap one Maven dep
    // (atmosphere-langchain4j via the runtime-langchain4j profile), no code
    // change, and the AgentRuntime resolver picks LangChain4j (priority 100)
    // over Built-in (priority 0). The outbound-MCP wiring continues to
    // surface the same upstream tools through AgentExecutionContext.tools()
    // — that's what the same admin endpoint check below verifies.
    const response = await page.goto(`${agent.baseUrl}/atmosphere/console/`);
    expect(response?.status()).toBe(200);
    await expect(page.getByText(/Runtime:\s*langchain4j/i)).toBeVisible({ timeout: 15_000 });
  });

  test('admin endpoint surfaces same tool inventory under LangChain4j', async () => {
    const response = await fetch(`${agent.baseUrl}/api/mcp-client/sources`);
    const body = await response.json() as { connected: boolean; toolCount: number };
    expect(body.connected).toBe(true);
    expect(body.toolCount).toBeGreaterThanOrEqual(1);
  });

  test('upstream-tools endpoint dispatches a real tool call (chrome-devtools layer)', async () => {
    // Needs a real model: the prompt below is natural language, and only a
    // model decides to call the upstream tool. Keyless (CI) the call never
    // happens and the counter stays at 0, so the test runs only when a key is
    // present. The keyless tests above still pin the connection, the
    // advertised tool inventory and the runtime swap.
    test.skip(!process.env.LLM_API_KEY && !process.env.GEMINI_API_KEY,
      'requires LLM_API_KEY (or GEMINI_API_KEY) for a real model to dispatch the tool');
    // Outbound-MCP roundtrip: the model picks the upstream tool, the agent's
    // runtime forwards it through McpToolSource to the upstream MCP server,
    // and the metrics counter increments. Confirms the
    // McpToolsInterceptor → McpToolSource path is live under LangChain4j
    // — the cross-runtime claim of this branch.
    const wsUrl = agent.baseUrl.replace('http://', 'ws://') + '/atmosphere/personal-assistant/upstream-tools';
    const ws = new WebSocket(wsUrl);
    await new Promise<void>((res, rej) => {
      const t = setTimeout(() => rej(new Error('ws connect timeout')), 10_000);
      ws.on('open', () => { clearTimeout(t); res(); });
      ws.on('error', (e) => { clearTimeout(t); rej(e); });
    });
    // Capture every wire frame so a regression in tool-event protocol
    // doesn't pass silently. Closing happens in afterAll via agent.stop().
    let frames = '';
    ws.on('message', (d) => { frames += d.toString(); });
    ws.send('What version of Atmosphere is on the upstream?');
    // 8s ceiling: dispatch + 1 LLM round-trip + tool callback + final
    // streamed response. If we exceed this, something is hung.
    await new Promise((res) => setTimeout(res, 8_000));
    ws.close();

    // The runtime emits tool-start framing before invoking McpToolSource.
    // Either the framing OR the metrics counter increment proves dispatch.
    const metrics = await fetch(`${agent.baseUrl}/api/mcp-client/sources`)
        .then((r) => r.json()) as { tools: Array<{ name: string; calls: number }> };
    const totalCalls = metrics.tools.reduce((s, t) => s + t.calls, 0);
    expect(totalCalls).toBeGreaterThanOrEqual(1);
    expect(frames).toMatch(/tool-start|atmosphere_version/);
  });
});
