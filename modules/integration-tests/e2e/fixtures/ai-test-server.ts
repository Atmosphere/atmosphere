import { type ChildProcess, spawn } from 'child_process';
import { resolve } from 'path';
import net from 'net';
import http from 'http';
import { ROOT, runtimeClasspath } from './packaged-build';

/**
 * Starts the AiFeatureTestServer (embedded Jetty with all AI endpoints).
 *
 * Plain `java -cp` over the classpath the module's build materialised in target/e2e-lib —
 * no Maven, and so no Maven Central, at test time. See runtimeClasspath() for why.
 */
export async function startAiTestServer(port: number): Promise<AiTestServer> {
  const cwd = resolve(ROOT, 'modules', 'integration-tests');

  const proc = spawn('java', [
    `-Dserver.port=${port}`,
    '-cp', runtimeClasspath('modules/integration-tests'),
    'org.atmosphere.integrationtests.ai.AiFeatureTestServer',
  ], {
    cwd,
    env: process.env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let output = '';
  proc.stdout?.on('data', (d) => { output += d.toString(); });
  proc.stderr?.on('data', (d) => { output += d.toString(); });

  const died = rejectOnEarlyExit(proc);

  try {
    await Promise.race([waitForPort(port, 60_000), died]);
    await Promise.race([waitForHttpResponse(port, 30_000), died]);
  } catch (e) {
    proc.kill('SIGTERM');
    console.error(`=== AiTestServer output ===\n${output.slice(-3000)}`);
    throw new Error(`Failed to start AiFeatureTestServer: ${e}`);
  }

  return new AiTestServer(proc, port, output);
}

/**
 * Rejects if the spawned process exits before the port opens.
 *
 * A dead child cannot open a port, but the startup wait had no way to know that: it
 * kept polling for the full timeout after the JVM had already exited, turning a one-line
 * boot error — a missing main class, a stale classpath, a port already bound —
 * into an opaque "port not ready after Nms". Racing the poll against process exit
 * surfaces the real cause immediately, and the captured output goes with it.
 */
function rejectOnEarlyExit(proc: ChildProcess): Promise<never> {
  const p = new Promise<never>((_, reject) => {
    proc.once('exit', (code, signal) => {
      const how = signal ? `signal ${signal}` : `exit code ${code}`;
      reject(new Error(`process exited before the port opened (${how})`));
    });
  });
  // The happy path never awaits this promise, so swallow its rejection to keep
  // Node from reporting an unhandled rejection once the port does open.
  p.catch(() => {});
  return p;
}

/**
 * Waits until the server answers an HTTP request — any status.
 *
 * An open port is not a ready server: Jetty binds its listening socket before it starts
 * the servlet tree, so a TCP connect succeeds while Atmosphere is still initialising. The
 * server initialises on start (EmbeddedAtmosphereServer.withInitOnStart), so the first
 * response, whatever its status, means the framework and every handler are in place. A
 * cold JVM made the gap wide enough for the first WebSocket of a spec to time out.
 */
async function waitForHttpResponse(port: number, timeoutMs: number): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      await new Promise<void>((ok, fail) => {
        const req = http.get({ host: '127.0.0.1', port, path: '/', timeout: 5_000 }, (res) => {
          res.resume();
          ok();
        });
        req.on('timeout', () => req.destroy(new Error('no response')));
        req.on('error', fail);
      });
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  throw new Error(`Port ${port} accepted connections but never answered HTTP within ${timeoutMs}ms`);
}

async function waitForPort(port: number, timeoutMs = 30_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      await new Promise<void>((ok, fail) => {
        const sock = net.createConnection(port, '127.0.0.1');
        sock.once('connect', () => { sock.destroy(); ok(); });
        sock.once('error', fail);
      });
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  throw new Error(`Port ${port} not ready after ${timeoutMs}ms`);
}

export class AiTestServer {
  private output: string;

  constructor(
    private proc: ChildProcess,
    public readonly port: number,
    output: string,
  ) {
    this.output = output;
    proc.stdout?.on('data', (d) => { this.output += d.toString(); });
    proc.stderr?.on('data', (d) => { this.output += d.toString(); });
  }

  get baseUrl(): string {
    return `http://localhost:${this.port}`;
  }

  get wsUrl(): string {
    return `ws://localhost:${this.port}`;
  }

  getOutput(): string {
    return this.output;
  }

  async stop(): Promise<void> {
    if (this.proc.killed) return;
    this.proc.kill('SIGTERM');
    await new Promise<void>((resolve) => {
      const timeout = setTimeout(() => {
        this.proc.kill('SIGKILL');
        resolve();
      }, 5000);
      this.proc.once('exit', () => {
        clearTimeout(timeout);
        resolve();
      });
    });
  }
}
