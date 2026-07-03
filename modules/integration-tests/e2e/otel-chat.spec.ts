import { test, expect } from '@playwright/test';
import { startSample, SAMPLES, type SampleServer } from './fixtures/sample-server';
import { connectWebSocket, waitFor } from './helpers/transport-helper';
import { existsSync } from 'fs';
import { resolve } from 'path';

// __dirname is modules/integration-tests/e2e — three levels below the repo root.
// This was four ups, which overshot the root: OTEL_TARGET never existed, hasJar
// was always false, and the whole suite silently skipped in CI — which is why
// the OTLP dependency-skew regression shipped with an e2e spec that "covered"
// it. Three ups resolves the root correctly so these tests actually run.
const ROOT = resolve(__dirname, '..', '..', '..');
const OTEL_TARGET = resolve(ROOT, 'samples', 'spring-boot-otel-chat', 'target');

// Skip the entire suite if the OTel sample JAR wasn't built
const hasJar = existsSync(OTEL_TARGET) &&
  require('fs').readdirSync(OTEL_TARGET).some((f: string) =>
    f.endsWith('.jar') && !f.endsWith('-sources.jar') && !f.endsWith('-javadoc.jar'));

(hasJar ? test.describe : test.describe.skip)('Spring Boot OTel Chat', () => {
  let server: SampleServer;

  test.beforeAll(async () => {
    server = await startSample(SAMPLES['spring-boot-otel-chat']);
  });

  test.afterAll(async () => {
    await server?.stop();
  });

  test('page loads and serves the chat UI', async ({ page }) => {
    await page.goto(server.baseUrl + '/atmosphere/console/');
    await page.getByTestId('chat-layout').waitFor({ state: 'visible' });
  });

  test('client can connect via WebSocket', async () => {
    const { ws, close } = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    expect(ws.readyState).toBe(1); // OPEN
    close();
  });

  test('messages are broadcast between clients', async () => {
    const client1 = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    const client2 = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    await new Promise(r => setTimeout(r, 500));

    client1.ws.send(JSON.stringify({ author: 'OTel-User', message: 'Traced message!' }));

    await waitFor(() => client2.messages.some(m => m.includes('Traced message!')));

    client1.close();
    client2.close();
  });

  test('server logs indicate tracing is active', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    await new Promise(r => setTimeout(r, 500));

    client.ws.send('Hello with tracing');
    await new Promise(r => setTimeout(r, 1000));

    const output = server.getOutput();
    expect(output).toContain('tracing active');

    client.close();
  });

  // Regression: the OTel dep set was skewed (api 1.63 managed, sdk/exporter fell
  // to 1.55), so the OTLP span exporter died at EXPORT time with
  // NoClassDefFoundError io/opentelemetry/api/internal/InstrumentationUtil and no
  // span ever reached a collector — yet the app booted and "tracing active"
  // logged, so the shallow log check above passed while tracing was broken.
  //
  // No Jaeger runs in this lane, so we cannot assert a span ARRIVES; instead we
  // assert the export CODE PATH is healthy. The BatchSpanProcessor exports
  // asynchronously after span end, so drive traffic, let a schedule window
  // elapse, then assert the SDK initialised for real (not the noop fallback)
  // and the export worker never hit a class-linkage failure. A connection error
  // to the absent collector is fine — that is a healthy export attempt; a
  // NoClassDefFoundError is the version-skew regression. The positive path
  // (a span actually exports) is pinned deterministically without Docker by
  // OtlpExporterLinkageTest in the sample's own unit tests.
  test('OTLP span export links cleanly (no dependency skew)', async () => {
    const client = await connectWebSocket(server.baseUrl, '/atmosphere/ai-chat');
    await new Promise(r => setTimeout(r, 500));

    for (let i = 0; i < 3; i++) {
      client.ws.send(`export-trace-${i}`);
      await new Promise(r => setTimeout(r, 300));
    }
    // Give the BatchSpanProcessor time to attempt an export.
    await new Promise(r => setTimeout(r, 3000));
    client.close();

    const output = server.getOutput();
    // The sample's OtelConfig logs one or the other at startup.
    expect(output, 'OpenTelemetry SDK must initialise (not fall back to noop)')
      .not.toMatch(/OpenTelemetry SDK initialization failed|using noop/);
    // The exact version-skew symptom must be absent after export ran.
    expect(output, 'the OTLP exporter must not hit a class-linkage failure on export')
      .not.toMatch(/NoClassDefFoundError|InstrumentationUtil|NoSuchMethodError/);
  });
});
