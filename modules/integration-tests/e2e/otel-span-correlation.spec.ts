import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';
import { existsSync } from 'fs';
import { resolve } from 'path';

// Three ups reaches the repo root from modules/integration-tests/e2e; a four-up
// path here overshot it so hasJar was always false and this suite dead-skipped.
const ROOT = resolve(__dirname, '..', '..', '..');
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

  test('OpenTelemetry SDK initialised at startup (runtime truth)', async () => {
    // The old assertion looked for the per-message string 'tracing active'
    // without sending a message, so it depended on ordering and — once the spec
    // stopped dead-skipping — failed. Assert the startup-time signal instead:
    // OtelConfig logs one line on success and a different one when it falls back
    // to a noop tracer, so this proves tracing is really wired, not just that a
    // message was echoed.
    const output = server.getOutput();
    expect(output, 'OpenTelemetry SDK must initialise (not the noop fallback)')
      .toContain('OpenTelemetry SDK initialized');
    expect(output).not.toMatch(/initialization failed|using noop/);
  });

  test('WebSocket message triggers a traced span that exports cleanly', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    await new Promise(r => setTimeout(r, 500));

    client.ws.send(JSON.stringify({ author: 'OTelSpan', message: 'Traced message!' }));
    // Let the span end and the BatchSpanProcessor attempt an async export.
    await new Promise(r => setTimeout(r, 3000));

    // `output.length > 0` proved nothing — it stayed true while the OTLP
    // exporter died on a dependency-version skew (NoClassDefFoundError
    // InstrumentationUtil) and no span ever exported. Assert the message was
    // handled with tracing active AND the export path linked cleanly (a
    // connection error to the absent collector is fine; a class-linkage error
    // is the regression).
    const output = server.getOutput();
    expect(output, 'the message handler must run with tracing active').toContain('tracing active');
    expect(output, 'span export must not hit a class-linkage failure')
      .not.toMatch(/NoClassDefFoundError|InstrumentationUtil|NoSuchMethodError|using noop/);

    client.close();
  });

  test('multiple messages create independent spans', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
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
    const client1 = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    const client2 = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
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
