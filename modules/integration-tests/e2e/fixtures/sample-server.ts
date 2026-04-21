import { type ChildProcess, spawn } from 'child_process';
import { resolve } from 'path';
import { readdirSync } from 'fs';
import net from 'net';
import { WebSocket } from 'ws';

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
  /** Atmosphere endpoint path to check for readiness (e.g. /atmosphere/ai-chat) */
  readyPath?: string;
  /** Skip WebSocket readiness probe — use for endpoints that only serve HTTP */
  httpOnlyReady?: boolean;
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
    env: {
      // Admin writes opt in via env so the default (out-of-box) posture
      // stays fail-closed, while admin-quarkus.spec.ts can authenticate
      // with X-Atmosphere-Auth: demo-token against the configured token.
      ATMOSPHERE_ADMIN_HTTP_WRITE_ENABLED: 'true',
      ATMOSPHERE_ADMIN_AUTH_TOKEN: 'demo-token',
    },
  },
  'spring-boot-ai-chat': {
    name: 'spring-boot-ai-chat',
    dir: 'spring-boot-ai-chat',
    port: 8080,
    type: 'spring-boot',
    readyPath: '/atmosphere/ai-chat',
    // Sample defaults to atmosphere.auth.enabled=false (out-of-box demo
     // posture per its application.properties). Auth-enforcement specs
     // (auth-token.spec, auth-oauth-jwt.spec) require it on, so flip it
     // back here for every e2e run; tests that don't care still pass.
    env: {
      ATMOSPHERE_AUTH_ENABLED: 'true',
      ATMOSPHERE_AUTH_TOKEN: 'demo-token',
      ATMOSPHERE_ADMIN_HTTP_WRITE_ENABLED: 'true',
    },
  },
  'spring-boot-mcp-server': {
    name: 'spring-boot-mcp-server',
    dir: 'spring-boot-mcp-server',
    port: 8083,
    type: 'spring-boot',
    readyPath: '/atmosphere/ai-chat',
  },
  'spring-boot-ai-classroom': {
    name: 'spring-boot-ai-classroom',
    dir: 'spring-boot-ai-classroom',
    port: 8085,
    type: 'spring-boot',
    readyPath: '/atmosphere/classroom/general',
  },
  'spring-boot-durable-sessions': {
    name: 'spring-boot-durable-sessions',
    dir: 'spring-boot-durable-sessions',
    port: 8084,
    type: 'spring-boot',
    readyPath: '/atmosphere/chat',
  },
  'spring-boot-reattach-harness': {
    name: 'spring-boot-reattach-harness',
    dir: 'spring-boot-reattach-harness',
    port: 8096,
    type: 'spring-boot',
    // The harness has no websocket chat endpoint at / — just the
    // @AiEndpoint under /atmosphere/agent/harness plus the REST
    // /harness/synthetic-run surface. HTTP ready-probe against the
    // AI endpoint is enough to know the framework is up.
    readyPath: '/atmosphere/agent/harness/',
    httpOnlyReady: true,
  },
  'spring-boot-otel-chat': {
    name: 'spring-boot-otel-chat',
    dir: 'spring-boot-otel-chat',
    port: 8090,
    type: 'spring-boot',
    readyPath: '/atmosphere/ai-chat',
  },
  'spring-boot-ai-tools': {
    name: 'spring-boot-ai-tools',
    dir: 'spring-boot-ai-tools',
    port: 8091,
    type: 'spring-boot',
    readyPath: '/atmosphere/ai-chat',
  },
  'spring-boot-rag-chat': {
    name: 'spring-boot-rag-chat',
    dir: 'spring-boot-rag-chat',
    port: 8092,
    type: 'spring-boot',
    readyPath: '/atmosphere/console/',
    httpOnlyReady: true,
  },
  'spring-boot-a2a-agent': {
    name: 'spring-boot-a2a-agent',
    dir: 'spring-boot-a2a-agent',
    port: 8093,
    type: 'spring-boot',
  },
  'spring-boot-agui-chat': {
    name: 'spring-boot-agui-chat',
    dir: 'spring-boot-agui-chat',
    port: 8094,
    type: 'spring-boot',
  },
  'spring-boot-multi-agent-startup-team': {
    name: 'spring-boot-multi-agent-startup-team',
    dir: 'spring-boot-multi-agent-startup-team',
    port: 8095,
    type: 'spring-boot',
    readyPath: '/atmosphere/agent/ceo',
  },
  'spring-boot-dentist-agent': {
    name: 'spring-boot-dentist-agent',
    dir: 'spring-boot-dentist-agent',
    port: 8096,
    type: 'spring-boot',
    readyPath: '/atmosphere/agent/dentist',
  },
  'spring-boot-orchestration-demo': {
    name: 'spring-boot-orchestration-demo',
    dir: 'spring-boot-orchestration-demo',
    port: 8097,
    type: 'spring-boot',
    readyPath: '/atmosphere/agent/support',
  },
  'spring-boot-channels-chat': {
    name: 'spring-boot-channels-chat',
    dir: 'spring-boot-channels-chat',
    port: 8104,
    type: 'spring-boot',
    readyPath: '/atmosphere/ai-chat',
  },
  'spring-boot-koog-chat': {
    name: 'spring-boot-koog-chat',
    dir: 'spring-boot-koog-chat',
    port: 8097,
    type: 'spring-boot',
    readyPath: '/atmosphere/ai-chat',
  },
  'spring-boot-semantic-kernel-chat': {
    name: 'spring-boot-semantic-kernel-chat',
    dir: 'spring-boot-semantic-kernel-chat',
    port: 8098,
    type: 'spring-boot',
    readyPath: '/atmosphere/ai-chat',
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
 * Wait for an HTTP endpoint to return a non-5xx response.
 * Handles the race between TCP port open and application ready.
 */
async function waitForHttp(url: string, timeoutMs = 30_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await fetch(url);
      if (res.status < 500) return; // Any non-server-error means the app is ready
    } catch {
      // Connection refused or fetch error — keep retrying
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`HTTP endpoint ${url} not ready after ${timeoutMs}ms`);
}

/**
 * Wait for a WebSocket endpoint to accept connections.
 * Opens a throwaway connection, waits for the 'open' event, then closes.
 * This eliminates the race between "HTTP responds" and "WebSocket layer initialized".
 */
async function waitForWebSocket(url: string, timeoutMs = 15_000): Promise<void> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      await new Promise<void>((ok, fail) => {
        const ws = new WebSocket(url);
        const timer = setTimeout(() => { ws.close(); fail(new Error('timeout')); }, 5_000);
        ws.once('open', () => { clearTimeout(timer); ws.close(); ok(); });
        ws.once('error', (e) => { clearTimeout(timer); fail(e); });
      });
      return;
    } catch {
      await new Promise((r) => setTimeout(r, 500));
    }
  }
  throw new Error(`WebSocket endpoint ${url} not ready after ${timeoutMs}ms`);
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
    // Port open doesn't mean the app is ready — wait for HTTP 200
    await waitForHttp(`http://127.0.0.1:${config.port}/`, 30_000);
    // Wait for the Atmosphere endpoint to be initialized (servlet may
    // start after the web server is ready, especially on slow CI runners)
    if (config.readyPath) {
      await waitForHttp(`http://127.0.0.1:${config.port}${config.readyPath}`, 30_000);
      if (!config.httpOnlyReady) {
        // Verify the WebSocket layer is fully initialized (not just HTTP)
        const wsUrl = `ws://127.0.0.1:${config.port}${config.readyPath}`;
        await waitForWebSocket(wsUrl, 15_000);
      }
    }
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

  /**
   * Get the last N lines of server output (for failure debugging).
   */
  getRecentOutput(lines = 200): string {
    const allLines = this.output.split('\n');
    return allLines.slice(-lines).join('\n');
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
