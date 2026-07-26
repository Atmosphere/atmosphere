import { test, expect } from '@playwright/test';
import { type ChildProcess, spawn } from 'child_process';
import { resolve } from 'path';
import { existsSync, readdirSync } from 'fs';
import { WebSocket } from 'ws';
import net from 'net';

/**
 * Kotlin DSL E2E — boots the SHADED fat jar and drives its wire protocol.
 *
 * The sample now answers through a real agent declared with the Kotlin agent
 * DSL (`registerAgent { }`), so this spec also asserts the agent actually
 * landed in the framework's routing table and that its replies come back over
 * the transport DSL's endpoint. The JVM is started with every provider key
 * scrubbed from its environment so the agent resolves the framework's offline
 * demo runtime and the answers stay deterministic — a developer with
 * GEMINI_API_KEY exported must not turn this suite into a live model call.
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

// Self-guard: in CI the shaded jar MUST exist (the reactor build packages it),
// so a missing jar is a build wiring bug. Fail loud rather than silently skip —
// a silent skip is exactly the false-confidence trap this spec was rescued from.
if (process.env.CI && !jar) {
  throw new Error(
    'kotlin-dsl-chat shaded jar not found in CI — the reactor build must package it; ' +
    'refusing to skip silently (see TARGET: ' + TARGET + ')');
}

(jar ? test.describe : test.describe.skip)('Kotlin DSL chat (shaded jar)', () => {
  let proc: ChildProcess;
  let output = '';

  test.beforeAll(async () => {
    test.setTimeout(120_000);
    // Scrub provider credentials from the child env: the sample's agent
    // resolves its runtime the same way an @Agent does, so a key in the
    // developer's shell would swap the deterministic offline runtime for a
    // live model and make these assertions meaningless.
    const env = { ...process.env };
    for (const key of ['LLM_API_KEY', 'LLM_MODE', 'LLM_MODEL', 'LLM_BASE_URL',
      'OPENAI_API_KEY', 'GEMINI_API_KEY', 'ANTHROPIC_API_KEY']) {
      delete env[key];
    }
    proc = spawn('java', [`-Dserver.port=${PORT}`, '-jar', jar as string], {
      cwd: resolve(ROOT, 'samples', 'kotlin-dsl-chat'),
      stdio: ['ignore', 'pipe', 'pipe'],
      env,
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

  test('the DSL-declared agent is registered as a framework endpoint', async () => {
    // The Kotlin agent DSL registers through the framework's own machinery, so
    // the agent must be routable at the same /atmosphere/agent/{name} mapping
    // an @Agent-annotated class produces. An unregistered agent path 404s —
    // that contrast is what proves registration, not merely that the app boots.
    const registered = await fetch(
      `http://127.0.0.1:${PORT}/atmosphere/agent/kotlin-dsl-chat`,
      { method: 'POST', body: 'ping' });
    expect(registered.status, 'the DSL agent must be routable').toBe(200);

    const unknown = await fetch(
      `http://127.0.0.1:${PORT}/atmosphere/agent/not-declared`,
      { method: 'POST', body: 'ping' });
    expect(unknown.status, 'an undeclared agent path must not be routed').toBe(404);

    // Runtime truth: the agent resolved the offline demo runtime (no keys in
    // this JVM's env) and the lambda-declared tool reached the tool registry.
    expect(output, 'the agent DSL must register through the framework')
      .toMatch(/agent 'kotlin-dsl-chat' registered at \/atmosphere\/agent\/kotlin-dsl-chat/);
    expect(output, 'the resolved runtime and the DSL tool must be reported')
      .toMatch(/runtime: demo, tools: \[word_count\]/);
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

    // The replies now come from the DSL-declared agent's AI pipeline (offline
    // demo runtime), delivered by the transport DSL's coroutine broadcast.
    expect(msgs, 'the DSL agent answers ping with pong').toContain('pong');
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
