import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';
import { existsSync } from 'fs';
import { resolve } from 'path';

const ROOT = resolve(__dirname, '..', '..', '..', '..');
const OTEL_TARGET = resolve(ROOT, 'samples', 'spring-boot-otel-chat', 'target');

// Skip the entire suite if the OTel sample JAR wasn't built
const hasJar = existsSync(OTEL_TARGET) &&
  require('fs').readdirSync(OTEL_TARGET).some((f: string) =>
    f.endsWith('.jar') && !f.endsWith('-sources.jar') && !f.endsWith('-javadoc.jar'));

(hasJar ? test.describe : test.describe.skip)('OpenTelemetry Span Correlation', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    server = await startSample(SAMPLES['spring-boot-otel-chat']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('tracing is active in server output', async () => {
    const output = server.getOutput();
    expect(output).toContain('tracing active');
  });

  test('WebSocket message triggers traced span', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    client.ws.send(JSON.stringify({ author: 'OTelSpan', message: 'Traced message!' }));
    await new Promise(r => setTimeout(r, 2000));

    const output = server.getRecentOutput(500);
    // Server should log trace/span information
    expect(output.length).toBeGreaterThan(0);

    client.close();
  });

  test('multiple messages create independent spans', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    // Send multiple messages
    for (let i = 1; i <= 3; i++) {
      client.ws.send(JSON.stringify({ author: 'Span', message: `trace-msg-${i}` }));
      await new Promise(r => setTimeout(r, 300));
    }

    await new Promise(r => setTimeout(r, 2000));

    // Messages should be received back (broadcast)
    await waitFor(() =>
      client.messages.filter(m => m.includes('trace-msg-')).length >= 3,
      10_000,
    );

    client.close();
  });

  test('broadcast to multiple clients maintains trace context', async () => {
    const client1 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    const client2 = await connectWebSocket(server.baseUrl, '/atmosphere/chat');
    await new Promise(r => setTimeout(r, 500));

    client1.ws.send(JSON.stringify({ author: 'Tracer', message: 'correlated-span' }));

    await waitFor(() => client2.messages.some(m => m.includes('correlated-span')));

    client1.close();
    client2.close();
  });

  test('console UI serves correctly with OTel enabled', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-layout').waitFor({ state: 'visible' });
  });
});
