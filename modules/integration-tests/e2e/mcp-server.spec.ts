import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';

let server: SampleServer;

test.beforeAll(async () => {
  server = await startSample(SAMPLES['spring-boot-mcp-server']);
});

test.afterAll(async () => {
  await server?.stop();
});

test.describe('MCP Server Chat', () => {
  test('WebSocket connects to chat endpoint', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    expect(client.ws.readyState).toBe(1); // OPEN
    client.close();
  });

  // Regression: the chat @ManagedService was mis-mapped to /atmosphere/ai-chat
  // while the frontend connects to /atmosphere/chat, so messages 404'd and never
  // delivered. With the paths aligned, the broadcast round-trips.
  test('user can send and receive messages', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    const msg = JSON.stringify({ author: 'Alice', message: 'Hello from MCP!' });
    sender.ws.send(msg);

    await waitFor(
      () => receiver.messages.some(m => m.includes('Hello from MCP!')),
      10_000,
    );

    const received = receiver.messages.find(m => m.includes('Hello from MCP!'));
    expect(received).toBeDefined();

    sender.close();
    receiver.close();
  });

  test('message includes author', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    const msg = JSON.stringify({ author: 'Eve', message: 'MCP chat works!' });
    sender.ws.send(msg);

    await waitFor(
      () => receiver.messages.some(m => m.includes('Eve') && m.includes('MCP chat works!')),
      10_000,
    );

    sender.close();
    receiver.close();
  });

  test('multiple messages delivered in order', async () => {
    const sender = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const receiver = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    for (let i = 1; i <= 3; i++) {
      sender.ws.send(JSON.stringify({ author: 'Sender', message: `msg-${i}` }));
      await new Promise(r => setTimeout(r, 100));
    }

    await waitFor(
      () => receiver.messages.filter(m => m.includes('msg-')).length >= 3,
      10_000,
    );

    for (let i = 1; i <= 3; i++) {
      expect(receiver.messages.some(m => m.includes(`msg-${i}`))).toBe(true);
    }

    sender.close();
    receiver.close();
  });

  // Regression (F3): the console's static <meta> CSP carried frame-src 'self',
  // which blocked the MCP Apps sandbox from framing its distinct sibling origin
  // (localhost<->127.0.0.1) — the "Server Clock" app showed "This content is
  // blocked". The CSP is now a server response header on index.html with
  // frame-src widened to the sandbox origin, so the cross-origin sandbox loads
  // and the inner app renders. The sandbox proxy itself gets NO restrictive CSP
  // so its inline bootstrap script can run.
  test('MCP Apps sandbox iframe loads cross-origin', async ({ page }) => {
    const cspErrors: string[] = [];
    page.on('console', (m) => {
      if (/Content Security Policy|frame-src/i.test(m.text())) {
        cspErrors.push(m.text());
      }
    });

    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('tab-apps').click();
    await page.getByTestId('mcp-app-clock_app').click();

    const frame = page.locator('[data-testid="mcp-app-frame"]');
    await expect(frame).toBeVisible();
    // Cross-origin sibling → proxy double-iframe (not the same-origin srcdoc path).
    await expect(frame).toHaveAttribute('data-sandbox-mode', 'proxy');

    // Drill through the sandbox proxy into the inner app document and assert it
    // actually rendered — proof the cross-origin frame was NOT CSP-blocked.
    const inner = page
      .frameLocator('[data-testid="mcp-app-frame"]')
      .frameLocator('iframe');
    await expect(inner.getByTestId('mcp-app-label')).toHaveText('Atmosphere MCP App', {
      timeout: 10_000,
    });
    // The clock starts at "--:--:--" and its inline script ticks it to a real
    // time — proof the inner app's script ran (sandbox CSP not over-restricted).
    await expect(inner.getByTestId('mcp-clock')).not.toHaveText('--:--:--', {
      timeout: 10_000,
    });

    expect(cspErrors, `CSP errors logged: ${cspErrors.join(' | ')}`).toEqual([]);
  });
});
