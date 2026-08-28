import { type ChildProcess, spawn } from 'child_process';
import { resolve } from 'path';
import net from 'net';

const ROOT = resolve(__dirname, '..', '..', '..', '..');

/**
 * Starts the DualTransportChatServer (Jetty + gRPC sharing same AtmosphereFramework).
 */
export async function startDualTransportServer(httpPort: number, grpcPort: number): Promise<DualServer> {
  const mvnw = resolve(ROOT, 'mvnw');
  const cwd = resolve(ROOT, 'modules', 'integration-tests');

  const proc = spawn(mvnw, [
    '-B', 'exec:java',
    `-Dexec.mainClass=org.atmosphere.integrationtests.DualTransportChatServer`,
    `-Dserver.port=${httpPort}`,
    `-Dgrpc.port=${grpcPort}`,
  ], {
    cwd,
    env: process.env,
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  let output = '';
  proc.stdout?.on('data', (d) => { output += d.toString(); });
  proc.stderr?.on('data', (d) => { output += d.toString(); });

  try {
    await waitForPort(httpPort, 45_000);
    await waitForPort(grpcPort, 10_000);
  } catch (e) {
    proc.kill('SIGTERM');
    console.error(`=== DualTransportServer output ===\n${output.slice(-2000)}`);
    throw new Error(`Failed to start DualTransportServer: ${e}`);
  }

  return new DualServer(proc, httpPort, grpcPort, output);
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

export class DualServer {
  private output: string;

  constructor(
    private proc: ChildProcess,
    public readonly httpPort: number,
    public readonly grpcPort: number,
    output: string,
  ) {
    this.output = output;
    proc.stdout?.on('data', (d) => { this.output += d.toString(); });
    proc.stderr?.on('data', (d) => { this.output += d.toString(); });
  }

  get baseUrl(): string {
    return `http://localhost:${this.httpPort}`;
  }

  get wsUrl(): string {
    return `ws://localhost:${this.httpPort}`;
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
