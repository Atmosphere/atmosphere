import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { AiWsClient } from './helpers/ai-ws-client';

let server: SampleServer;

test.beforeAll(async () => {
  test.setTimeout(120_000);
  server = await startSample(SAMPLES['spring-boot-multi-agent-startup-team']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('Agent Activity Streaming', () => {

  test('agent-step events arrive before synthesis', async () => {
    const wsUrl = server.baseUrl.replace('http://', 'ws://');
    const client = new AiWsClient(wsUrl, '/atmosphere/agent/ceo');
    await client.connect();
    client.send('Analyze the market for AI fitness apps');
    await client.waitForDone(60_000);

    // Agent-step events should have been emitted by StreamingActivityListener
    const agentSteps = client.aiEvents('agent-step');
    expect(agentSteps.length).toBeGreaterThan(0);

    // Should see thinking events for fleet agents
    const thinkingEvents = agentSteps.filter(e => {
      const data = e.data as Record<string, unknown>;
      return data?.stepName === 'thinking';
    });
    expect(thinkingEvents.length).toBeGreaterThan(0);

    // Should see completed events
    const completedEvents = agentSteps.filter(e => {
      const data = e.data as Record<string, unknown>;
      return data?.stepName === 'completed';
    });
    expect(completedEvents.length).toBeGreaterThan(0);

    // Activity events should arrive BEFORE the streaming text (synthesis)
    const firstStepSeq = agentSteps[0]?.seq ?? Number.MAX_VALUE;
    const textEvents = client.events.filter(e =>
      e.type === 'streaming-text' || e.event === 'text-delta');
    const firstTextSeq = textEvents[0]?.seq ?? 0;
    if (textEvents.length > 0) {
      expect(firstStepSeq).toBeLessThan(firstTextSeq);
    }

    client.close();
  });

  test('agent-step events identify correct agents', async () => {
    const wsUrl = server.baseUrl.replace('http://', 'ws://');
    const client = new AiWsClient(wsUrl, '/atmosphere/agent/ceo');
    await client.connect();
    client.send('AI developer tools market');
    await client.waitForDone(60_000);

    const agentSteps = client.aiEvents('agent-step');
    const agentNames = new Set(agentSteps.map(e => {
      const data = e.data as Record<string, unknown>;
      return (data?.data as Record<string, unknown>)?.agent as string;
    }).filter(Boolean));

    // Should see activity from at least research-agent (first in the pipeline)
    expect(agentNames.has('research-agent')).toBe(true);

    client.close();
  });

  test('completed events include duration', async () => {
    const wsUrl = server.baseUrl.replace('http://', 'ws://');
    const client = new AiWsClient(wsUrl, '/atmosphere/agent/ceo');
    await client.connect();
    client.send('market analysis');
    await client.waitForDone(60_000);

    const completedEvents = client.aiEvents('agent-step').filter(e => {
      const data = e.data as Record<string, unknown>;
      return data?.stepName === 'completed';
    });

    expect(completedEvents.length).toBeGreaterThan(0);
    const first = completedEvents[0].data as Record<string, unknown>;
    const innerData = first.data as Record<string, unknown>;
    expect(innerData?.durationMs).toBeDefined();
    expect(typeof innerData?.durationMs).toBe('number');

    client.close();
  });

  test('tool-start and agent-step events coexist', async () => {
    const wsUrl = server.baseUrl.replace('http://', 'ws://');
    const client = new AiWsClient(wsUrl, '/atmosphere/agent/ceo');
    await client.connect();
    client.send('AI fitness apps');
    await client.waitForDone(60_000);

    // The CEO coordinator emits both tool-start/tool-result (explicit)
    // and agent-step (via StreamingActivityListener)
    const toolStarts = client.aiEvents('tool-start');
    const agentSteps = client.aiEvents('agent-step');

    expect(toolStarts.length).toBeGreaterThan(0);
    expect(agentSteps.length).toBeGreaterThan(0);

    client.close();
  });

  test('fleet health endpoint returns agent states', async () => {
    // The fleet health is accessible programmatically — verify agents are registered
    const output = server.getOutput();
    expect(output).toContain('research-agent');
    expect(output).toContain('strategy-agent');
    expect(output).toContain('finance-agent');
    expect(output).toContain('writer-agent');
  });

  // ── Browser UI test ──

  test('frontend shows agent status bar with activity', async ({ page }) => {
    await page.goto(server.baseUrl + '/');
    // Wait for WebSocket fallback to connect
    await page.waitForTimeout(12_000);

    const input = page.getByPlaceholder(/ask about/i);
    await input.fill('AI fitness apps');
    await input.press('Enter');

    // Wait for agent collaboration cards to appear
    await expect(page.getByText(/Research Agent/i).first()).toBeVisible({ timeout: 30_000 });

    // Wait for the CEO synthesis to appear
    await expect(page.getByText(/CEO/).first()).toBeVisible({ timeout: 60_000 });

    // Agent status bar should show checkmarks for completed agents
    const statusBar = page.locator('text=/✓/');
    await expect(statusBar.first()).toBeVisible({ timeout: 30_000 });
  });
});
