import { test, expect } from '@playwright/test';
import { type ChildProcess, spawn } from 'child_process';
import { resolve } from 'path';
import { existsSync, readdirSync } from 'fs';
import { WebSocket } from 'ws';
import net from 'net';

/**
 * Kotlin DSL E2E — boots the SHADED fat jar and drives its wire protocol.
 *
 * This used to be a `test.skip()` no-op ("tested via the CI job"), which gave
 * false confidence: the sample shipped a release-gate regression (WebSocket
 * upgrades answered 501 because the embedded Jetty context never provisioned
 * the jakarta.websocket container, and the shade plugin dropped logback-core so
 * SLF4J fell back to NOP) and no e2e caught it. The behaviour only breaks in the
 * PACKAGED artifact, so this spec boots `java -jar` directly — self-contained
 * (not the shared sample-server fixture, whose HTTP-root readiness probe does
 * not fit an endpoint-only sample) — and asserts the wire contract.
 */

// __dirname is modules/integration-tests/e2e — three levels below the repo root
// (e2e → integration-tests → modules → root). The otel specs shipped a four-up
// path here, which overshot the root so the jar was never found and the suite
// silently skipped in CI; keep this at three.
const ROOT = resolve(__dirname, '..', '..', '..');
const TARGET = resolve(ROOT, 'samples', 'kotlin-dsl-chat', 'target');
const PORT = 8099;

// Skip only if the jar isn't built (so a missing package step doesn't fail the
// whole suite) — but the jar existing means we assert real behaviour.
function shadedJar(): string | null {
  if (!existsSync(TARGET)) return null;
  const jars = readdirSync(TARGET).filter(
    (f) => f.startsWith('atmosphere-kotlin-dsl-chat') && f.endsWith('.jar')
      && !f.endsWith('-sources.jar') && !f.endsWith('-javadoc.jar')
      && !f.startsWith('original-'),
  );
  return jars.length ? resolve(TARGET, jars[0]) : null;
}

function waitForPort(port: number, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolvePromise, reject) => {
    const tryOnce = () => {
      const sock = net.connect(port, '127.0.0.1');
      sock.once('connect', () => { sock.destroy(); resolvePromise(); });
      sock.once('error', () => {
        sock.destroy();
        if (Date.now() > deadline) reject(new Error(`port ${port} not open`));
        else setTimeout(tryOnce, 500);
      });
    };
    tryOnce();
  });
}

const jar = shadedJar();

(jar ? test.describe : test.describe.skip)('Kotlin DSL chat (shaded jar)', () => {
  let proc: ChildProcess;
  let output = '';

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    proc = spawn('java', [`-Dserver.port=${PORT}`, '-jar', jar as string], {
      cwd: resolve(ROOT, 'samples', 'kotlin-dsl-chat'),
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    proc.stdout?.on('data', (d) => { output += d.toString(); });
    proc.stderr?.on('data', (d) => { output += d.toString(); });
    await waitForPort(PORT, 90_000);
    // The endpoint suspends GETs; a POST is answered immediately, so use it as
    // the app-ready probe.
    for (let i = 0; i < 40; i++) {
      try {
        const res = await fetch(`http://127.0.0.1:${PORT}/chat`, { method: 'POST', body: 'ready-probe' });
        if (res.status === 200) break;
      } catch { /* not ready */ }
      await new Promise((r) => setTimeout(r, 500));
    }
  });

  test.afterAll(async () => {
    proc?.kill('SIGTERM');
    await new Promise((r) => setTimeout(r, 500));
  });

  test('WebSocket upgrade succeeds and the DSL agent answers', async () => {
    // Regression: without the jakarta.websocket ServerContainer the upgrade is
    // refused with 501 and the socket never opens.
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}/chat`);
    const msgs: string[] = [];
    ws.on('message', (d) => msgs.push(d.toString().trim()));
    const opened = await new Promise<boolean>((r) => {
      ws.on('open', () => r(true));
      ws.on('error', () => r(false));
      setTimeout(() => r(false), 8000);
    });
    expect(opened, 'WebSocket upgrade must be accepted (not 501)').toBe(true);

    ws.send('ping');
    await new Promise((r) => setTimeout(r, 1500));
    ws.send('release-gate');
    await new Promise((r) => setTimeout(r, 1500));
    ws.close();

    expect(msgs, 'DeterministicAgent answers ping with pong').toContain('pong');
    expect(msgs, 'other messages are echoed').toContain('echo: release-gate');
  });

  test('logback is active in the shaded jar (not SLF4J NOP)', () => {
    // Regression: the shade plugin bundled logback-classic without logback-core,
    // so SLF4J failed to instantiate the provider and fell back to a NOP logger.
    // The sample logs an INFO line on startup — its presence proves a real
    // logging backend is wired; the NOP-fallback warning proves it isn't.
    expect(output, 'SLF4J must not fall back to NOP (logback-core present)')
      .not.toMatch(/No SLF4J providers were found|NOP.*logger|Unable to get public no-arg constructor/);
    expect(output, 'the sample must emit a real log line')
      .toMatch(/Kotlin DSL chat started|Atmosphere Framework .* started/);
  });
});
