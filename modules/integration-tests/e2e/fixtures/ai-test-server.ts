import { type ChildProcess, spawn } from 'child_process';
import { resolve } from 'path';
import net from 'net';

const ROOT = resolve(__dirname, '..', '..', '..', '..');

/**
 * Starts the AiFeatureTestServer (embedded Jetty with all AI endpoints).
 */
export async function startAiTestServer(port: number): Promise<AiTestServer> {
  const mvnw = resolve(ROOT, 'mvnw');
  const cwd = resolve(ROOT, 'modules', 'integration-tests');

  const proc = spawn(mvnw, [
    '-B', 'exec:java',
    `-Dexec.mainClass=org.atmosphere.integrationtests.ai.AiFeatureTestServer`,
    `-Dserver.port=${port}`,
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
 * kept polling for the full timeout after Maven had already exited, turning a one-line
 * Maven error — an unresolvable plugin, a missing build extension, a bad mainClass —
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
