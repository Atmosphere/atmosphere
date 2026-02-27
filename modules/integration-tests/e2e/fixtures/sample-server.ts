import { type ChildProcess, spawn } from 'child_process';
import { resolve } from 'path';
import { readdirSync } from 'fs';
import net from 'net';

const ROOT = resolve(__dirname, '..', '..', '..', '..');

/** Sample application configuration. */
export interface SampleConfig {
  name: string;
  /** Directory under samples/ */
  dir: string;
  port: number;
  /** How to start: spring-boot JAR, embedded-jetty JAR, quarkus, or jetty-war (mvn jetty:run) */
  type: 'spring-boot' | 'embedded-jetty' | 'quarkus' | 'jetty-war';
  /** Extra environment variables (e.g. API keys) */
  env?: Record<string, string>;
  /** Extra JVM args */
  jvmArgs?: string[];
  /** Main class for embedded-jetty type (exec:java) */
  mainClass?: string;
}

export const SAMPLES: Record<string, SampleConfig> = {
  'chat': {
    name: 'chat',
    dir: 'chat',
    port: 8080,
    type: 'jetty-war',
  },
  'spring-boot-chat': {
    name: 'spring-boot-chat',
    dir: 'spring-boot-chat',
    port: 8080,
    type: 'spring-boot',
  },
  'embedded-jetty-chat': {
    name: 'embedded-jetty-chat',
    dir: 'embedded-jetty-websocket-chat',
    port: 8080,
    type: 'embedded-jetty',
    mainClass: 'org.atmosphere.samples.chat.EmbeddedJettyWebSocketChat',
  },
  'quarkus-chat': {
    name: 'quarkus-chat',
    dir: 'quarkus-chat',
    port: 8080,
    type: 'quarkus',
  },
  'spring-boot-ai-chat': {
    name: 'spring-boot-ai-chat',
    dir: 'spring-boot-ai-chat',
    port: 8080,
    type: 'spring-boot',
  },
  'spring-boot-langchain4j-chat': {
    name: 'spring-boot-langchain4j-chat',
    dir: 'spring-boot-langchain4j-chat',
    port: 8081,
    type: 'spring-boot',
  },
  'spring-boot-embabel-chat': {
    name: 'spring-boot-embabel-chat',
    dir: 'spring-boot-embabel-chat',
    port: 8082,
    type: 'spring-boot',
  },
  'spring-boot-spring-ai-chat': {
    name: 'spring-boot-spring-ai-chat',
    dir: 'spring-boot-spring-ai-chat',
    port: 8083,
    type: 'spring-boot',
  },
  'spring-boot-mcp-server': {
    name: 'spring-boot-mcp-server',
    dir: 'spring-boot-mcp-server',
    port: 8083,
    type: 'spring-boot',
  },
  'spring-boot-durable-sessions': {
    name: 'spring-boot-durable-sessions',
    dir: 'spring-boot-durable-sessions',
    port: 8080,
    type: 'spring-boot',
  },
};

/**
 * Find the latest JAR in a sample's target/ directory.
 */
function findJar(sampleDir: string, type: string): string {
  const targetDir = resolve(ROOT, 'samples', sampleDir, 'target');

  if (type === 'quarkus') {
    return resolve(targetDir, 'quarkus-app', 'quarkus-run.jar');
  }

  const jars = readdirSync(targetDir).filter(
    (f) => f.endsWith('.jar') && !f.endsWith('-sources.jar') && !f.endsWith('-javadoc.jar'),
  );
  // Prefer the one with the latest SNAPSHOT version
  jars.sort().reverse();
  if (jars.length === 0) {
    throw new Error(`No JAR found in ${targetDir}. Run: ./mvnw package -pl samples/${sampleDir} -DskipTests`);
  }
  return resolve(targetDir, jars[0]);
}

/**
 * Wait for a TCP port to accept connections.
 */
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

/**
 * Check if a port is already in use.
 */
async function isPortInUse(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const sock = net.createConnection(port, '127.0.0.1');
    sock.once('connect', () => { sock.destroy(); resolve(true); });
    sock.once('error', () => resolve(false));
  });
}

/**
 * Start a sample application and return a handle to stop it.
 */
export async function startSample(config: SampleConfig): Promise<SampleServer> {
  if (await isPortInUse(config.port)) {
    throw new Error(`Port ${config.port} is already in use. Stop the conflicting process first.`);
  }

  const samplePath = resolve(ROOT, 'samples', config.dir);
  let proc: ChildProcess;
  const env = { ...process.env, ...(config.env ?? {}) };

  if (config.type === 'jetty-war') {
    // WAR-based samples use mvn jetty:run
    const mvnw = resolve(ROOT, 'mvnw');
    proc = spawn(mvnw, ['-B', `jetty:run`, `-Djetty.port=${config.port}`], {
      cwd: samplePath,
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } else if (config.type === 'embedded-jetty') {
    // Embedded Jetty samples use mvn exec:java with explicit mainClass
    const mvnw = resolve(ROOT, 'mvnw');
    proc = spawn(mvnw, ['-B', 'exec:java', `-Dexec.mainClass=${config.mainClass}`, `-Dserver.port=${config.port}`], {
      cwd: samplePath,
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } else {
    const jar = findJar(config.dir, config.type);
    const args = [...(config.jvmArgs ?? []), '-jar', jar];

    if (config.type === 'spring-boot') {
      args.push(`--server.port=${config.port}`);
    }

    proc = spawn('java', args, {
      cwd: samplePath,
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  }

  // Collect output for debugging
  let output = '';
  proc.stdout?.on('data', (d) => { output += d.toString(); });
  proc.stderr?.on('data', (d) => { output += d.toString(); });

  try {
    await waitForPort(config.port, 90_000);
  } catch (e) {
    proc.kill('SIGTERM');
    console.error(`=== Server output for ${config.name} ===\n${output.slice(-2000)}`);
    throw new Error(`Failed to start ${config.name}: ${e}`);
  }

  return new SampleServer(proc, config, output);
}

export class SampleServer {
  private output: string;

  constructor(
    private proc: ChildProcess,
    public readonly config: SampleConfig,
    output: string,
  ) {
    this.output = output;
    proc.stdout?.on('data', (d) => { this.output += d.toString(); });
    proc.stderr?.on('data', (d) => { this.output += d.toString(); });
  }

  get baseUrl(): string {
    return `http://localhost:${this.config.port}`;
  }

  get pid(): number | undefined {
    return this.proc.pid;
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

  /**
   * Restart the server (for durable-sessions testing).
   */
  async restart(): Promise<void> {
    await this.stop();
    const newServer = await startSample(this.config);
    this.proc = newServer.proc;
    this.output = newServer.output;
  }
}
