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

  try {
    await waitForPort(port, 60_000);
  } catch (e) {
    proc.kill('SIGTERM');
    console.error(`=== AiTestServer output ===\n${output.slice(-3000)}`);
    throw new Error(`Failed to start AiFeatureTestServer: ${e}`);
  }

  return new AiTestServer(proc, port, output);
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
