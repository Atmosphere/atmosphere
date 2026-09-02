import { type ChildProcess, spawn } from 'child_process';
import { resolve } from 'path';
import { existsSync, readdirSync } from 'fs';
import net from 'net';
import { WebSocket } from 'ws';
import { ROOT, reactorVersion } from './packaged-build';

/** Sample application configuration. */
export interface SampleConfig {
  name: string;
  /** Directory under samples/ */
  dir: string;
  port: number;
  /**
   * How the sample boots. Every type runs the artifact the build produced under plain
   * `java` — a Spring Boot fat jar, a shaded jar, quarkus-run.jar, or the WAR under the
   * jetty-runner the sample's build copies next to it. None of them invoke Maven: a Maven
   * goal at test time puts Maven Central on the critical path of every spec, and one HTTP
   * 429 from Central turns into an opaque "port not ready" timeout (see packaged-build.ts).
   */
  type: 'spring-boot' | 'embedded-jetty' | 'quarkus' | 'jetty-war';
  /** Extra environment variables (e.g. API keys) */
  env?: Record<string, string>;
  /** Extra JVM args */
  jvmArgs?: string[];
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
     // posture, set in application.yml). Auth-enforcement specs
     // (auth-token.spec, auth-oauth-jwt.spec) require it on, so flip it
     // back here for every e2e run; the unified-console spec presents the
     // demo token via ?token= so it still connects under this forced auth.
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
    readyPath: '/atmosphere/chat',
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
  'spring-boot-browser-agent': {
    name: 'spring-boot-browser-agent',
    dir: 'spring-boot-browser-agent',
    port: 8103,
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
  'spring-boot-passivation-agent': {
    name: 'spring-boot-passivation-agent',
    dir: 'spring-boot-passivation-agent',
    port: 8097,
    // Headless REST sample (POST /api/agent/pause|resume, GET /checkpoints/{id});
    // no chat endpoint. The '/' probe (status < 500) is enough for readiness.
    type: 'spring-boot',
  },
  'spring-boot-agui-chat': {
    name: 'spring-boot-agui-chat',
    dir: 'spring-boot-agui-chat',
    port: 8094,
    type: 'spring-boot',
    // The @Agent registers a real WS UI handler at /atmosphere/agent/assistant
    // and an AG-UI SSE endpoint at /atmosphere/agent/assistant/agui. Probe the
    // agent path so the servlet/WS layer is confirmed up before specs POST to it.
    readyPath: '/atmosphere/agent/assistant',
  },
  'spring-boot-multi-agent-startup-team': {
    name: 'spring-boot-multi-agent-startup-team',
    dir: 'spring-boot-multi-agent-startup-team',
    port: 8095,
    type: 'spring-boot',
    readyPath: '/atmosphere/agent/ceo',
    // Open the recorded-content read gate so the console Tape tab can read the
    // tape back (the tape holds pre-redaction content; default posture is 401).
    // This is the documented dev/demo config for viewing tapes.
    jvmArgs: ['-Datmosphere.admin.content-read-auth-required=false'],
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
  'spring-boot-coding-agent': {
    name: 'spring-boot-coding-agent',
    dir: 'spring-boot-coding-agent',
    port: 8098,
    type: 'spring-boot',
    // Sandbox + AgentResumeHandle demo. No websocket endpoint at /
    // pre-Phase-1.5 — REST-only surface, http-ready probe is enough.
    httpOnlyReady: true,
  },
  'spring-boot-guarded-email-agent': {
    name: 'spring-boot-guarded-email-agent',
    dir: 'spring-boot-guarded-email-agent',
    port: 8099,
    type: 'spring-boot',
    // Plan-and-Verify (Meijer). Driven through the Atmosphere Console's
    // Validation tab (/atmosphere/console/); / redirects there. No bespoke
    // UI and no websocket transport needed for the taint + SMT demos.
    httpOnlyReady: true,
  },
  'spring-boot-personal-assistant': {
    name: 'spring-boot-personal-assistant',
    dir: 'spring-boot-personal-assistant',
    port: 8100,
    type: 'spring-boot',
    readyPath: '/atmosphere/agent/primary-assistant',
  },
  'quarkus-ai-chat': {
    name: 'quarkus-ai-chat',
    dir: 'quarkus-ai-chat',
    port: 18810,
    type: 'quarkus',
    readyPath: '/atmosphere/ai-chat',
    // The sample defaults the OpenAI key to `dummy`; that lets the Quarkus
    // LangChain4j synthetic StreamingChatModel bean materialise (so the
    // atmosphere-quarkus-langchain4j bridge can wire it) without forcing a
    // real LLM call. Real round-trips happen in dev / chrome-devtools, not
    // in this admission test. QUARKUS_HTTP_PORT pins the listener — the
    // Quarkus type does not pass --server.port like the spring-boot type.
    env: {
      LLM_API_KEY: 'dummy-not-real',
      QUARKUS_HTTP_PORT: '18810',
    },
  },
  'spring-boot-checkpoint-agent': {
    name: 'spring-boot-checkpoint-agent',
    dir: 'spring-boot-checkpoint-agent',
    // Default in application.yml is 8095 (collides with multi-agent-startup-team).
    // The spring-boot launcher passes --server.port=${config.port}, so this wins.
    port: 8101,
    type: 'spring-boot',
    // @Coordinator(name = "dispatch") registers at /atmosphere/agent/<name>
    // (CoordinatorProcessor.java:170). Probe that for HTTP+WS readiness so
    // we know the coordinator is wired before the isolation test connects.
    readyPath: '/atmosphere/agent/dispatch',
    // SQLite checkpoint store writes to target/checkpoint.db by default.
    // Pin to an in-memory store for the isolation test so we don't poison
    // a real on-disk DB across runs (and so the JVM tear-down is clean).
    env: {
      ATMOSPHERE_CHECKPOINT_STORE: 'in-memory',
    },
  },
  'spring-boot-ms-governance-chat': {
    name: 'spring-boot-ms-governance-chat',
    dir: 'spring-boot-ms-governance-chat',
    // Default in application.yml is 8090 (collides with otel-chat).
    port: 8102,
    type: 'spring-boot',
    // @AiEndpoint(path = "/atmosphere/ms-governance") — the sample's
    // application.yml also points the bundled Console at this path via
    // atmosphere.console-endpoint, so the same readyPath probes both.
    readyPath: '/atmosphere/ms-governance',
  },
};

/**
 * The reactor version, read from the root pom. atmosphere-project declares no
 * <parent>, and "<version>" is not a substring of "<modelVersion>", so the
 * first match is the project's own version.
 */
/**
 * Resolve the sample's boot JAR for the CURRENT reactor version.
 *
 * This used to take `jars.sort().reverse()[0]` — "the latest SNAPSHOT". That
 * is wrong in three ways, and samples/<x>/target keeps one jar per version ever
 * packaged there (23 of 29 sample targets held 2-5 versions when this was
 * written), so all three are reachable:
 *
 *   1. String sort is not version sort. 4.0.7/4.0.8/4.0.9 are real released
 *      versions of this project and "9" > "6", so a leftover 4.0.9 jar wins
 *      over 4.0.66. Same trap at every 10x boundary (4.0.99 vs 4.0.100).
 *   2. "Latest present" is not "current". A sample that was not repackaged for
 *      this build boots the previous release and the spec goes green for code
 *      that is not in the artifact it tested.
 *   3. `original-*.jar` — the shade plugin's PRE-SHADE copy — was not excluded,
 *      and it sorts above the real artifact ('o' > 'a'). Measured on the real
 *      tree, this selector returned
 *      original-atmosphere-jetty-embedded-websocket-4.0.66-SNAPSHOT.jar, i.e.
 *      the unshaded jar, for the very samples that exist to catch shade
 *      regressions. Only the exec:java boot types spared them today; adding a
 *      shaded sample to SAMPLES with type 'spring-boot' would have booted it.
 *
 * Absent is a hard failure that names the stale artifacts, never a fallback.
 */
function findJar(sampleDir: string, type: string): string {
  const targetDir = resolve(ROOT, 'samples', sampleDir, 'target');
  const version = reactorVersion();

  if (type === 'quarkus') {
    // quarkus-run.jar is unversioned; the app jar under quarkus-app/app/ is the
    // only honest freshness signal for a quarkus-app/ left by an earlier build.
    const appDir = resolve(targetDir, 'quarkus-app', 'app');
    let appJars: string[] = [];
    try {
      appJars = readdirSync(appDir).filter((f) => f.endsWith('.jar'));
    } catch {
      throw new Error(`No quarkus-app in ${targetDir}. Run: ./mvnw package -pl samples/${sampleDir} -DskipTests`);
    }
    if (!appJars.some((f) => f.endsWith(`-${version}.jar`))) {
      throw new Error(
        `samples/${sampleDir}/target/quarkus-app is stale — no ${version} application jar ` +
          `(found: ${appJars.join(', ') || 'nothing'}). ` +
          `Run: ./mvnw package -pl samples/${sampleDir} -DskipTests`,
      );
    }
    return resolve(targetDir, 'quarkus-app', 'quarkus-run.jar');
  }

  const all = readdirSync(targetDir).filter(
    (f) =>
      f.endsWith('.jar') &&
      !f.startsWith('original-') &&
      !f.endsWith('-sources.jar') &&
      !f.endsWith('-javadoc.jar') &&
      !f.endsWith('-tests.jar'),
  );
  const current = all.filter((f) => f.endsWith(`-${version}.jar`)).sort();
  if (current.length === 0) {
    const stale = all.filter((f) => /-\d/.test(f));
    throw new Error(
      `No ${version} JAR in ${targetDir}.` +
        (stale.length
          ? ` Stale artifacts present and deliberately NOT booted: ${stale.join(', ')}.`
          : '') +
        ` Run: ./mvnw package -pl samples/${sampleDir} -DskipTests`,
    );
  }
  return resolve(targetDir, current[0]);
}

/**
 * Resolve a WAR sample's boot pair: the jetty-runner its build copied into
 * target/e2e-runner, and the one WAR under target/.
 *
 * The WAR is named by <finalName> and so carries no version — like quarkus-run.jar
 * above, a stale one is invisible to any filename check. Freshness is read off the
 * exploded webapp the war plugin assembled it from: every atmosphere-* jar in its
 * WEB-INF/lib must be the CURRENT reactor version. Absent or stale is a hard failure
 * that names the build command, never a fallback.
 */
function findWar(sampleDir: string): { runner: string; war: string } {
  const targetDir = resolve(ROOT, 'samples', sampleDir, 'target');
  const build = `./mvnw clean package -pl samples/${sampleDir} -DskipTests`;

  const runner = resolve(targetDir, 'e2e-runner', 'jetty-runner.jar');
  if (!existsSync(runner)) {
    throw new Error(`No jetty-runner.jar in ${targetDir}/e2e-runner. Run: ${build}`);
  }

  let wars: string[] = [];
  try {
    wars = readdirSync(targetDir).filter((f) => f.endsWith('.war'));
  } catch {
    throw new Error(`No target/ in samples/${sampleDir}. Run: ${build}`);
  }
  if (wars.length !== 1) {
    throw new Error(
      `Expected exactly one WAR in ${targetDir}, found: ${wars.join(', ') || 'none'}. Run: ${build}`,
    );
  }

  const version = reactorVersion();
  const explodedLib = resolve(targetDir, wars[0].replace(/\.war$/, ''), 'WEB-INF', 'lib');
  let libs: string[] = [];
  try {
    libs = readdirSync(explodedLib).filter((f) => f.startsWith('atmosphere-') && f.endsWith('.jar'));
  } catch {
    throw new Error(`No exploded webapp at ${explodedLib} to verify ${wars[0]} against. Run: ${build}`);
  }
  const stale = libs.filter((f) => !f.endsWith(`-${version}.jar`));
  if (libs.length === 0 || stale.length > 0) {
    throw new Error(
      `samples/${sampleDir}/target/${wars[0]} is stale — expected only ${version} atmosphere jars in ` +
        `${explodedLib} (found: ${libs.join(', ') || 'nothing'}). Run: ${build}`,
    );
  }

  return { runner, war: resolve(targetDir, wars[0]) };
}

/**
 * Wait for a TCP port to accept connections.
 */
/**
 * Rejects if the spawned process exits before the server becomes reachable.
 *
 * Without this, every wait below keeps polling for its full timeout after the process
 * has already died — turning a one-line boot error into an opaque "port not ready" or,
 * worse, a blown beforeAll hook that names no cause at all.
 */
function rejectOnEarlyExit(proc: ChildProcess): Promise<never> {
  const p = new Promise<never>((_, reject) => {
    proc.once('exit', (code, signal) => {
      const how = signal ? `signal ${signal}` : `exit code ${code}`;
      reject(new Error(`process exited before the server became reachable (${how})`));
    });
  });
  // The happy path never awaits this promise; swallow its rejection so Node does
  // not report an unhandled rejection once the server does come up.
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
    // The packaged WAR, deployed by the jetty-runner the sample's build copied into
    // target/e2e-runner (the same Jetty line as its jetty-maven-plugin).
    const { runner, war } = findWar(config.dir);
    proc = spawn('java', [...(config.jvmArgs ?? []), '-jar', runner, '--port', String(config.port), war], {
      cwd: samplePath,
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
  } else {
    const jar = findJar(config.dir, config.type);
    const args = [...(config.jvmArgs ?? [])];

    if (config.type === 'embedded-jetty') {
      // The shaded jar's manifest names the main class; the sample reads -Dserver.port.
      args.push(`-Dserver.port=${config.port}`);
    }
    args.push('-jar', jar);
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

  // A dead process satisfies none of the waits below, and together they can burn
  // 165s before giving up — long enough to blow the enclosing beforeAll hook and
  // report a timeout instead of the boot error that actually happened. Racing each
  // wait against process exit surfaces the real cause immediately.
  const died = rejectOnEarlyExit(proc);

  try {
    await Promise.race([waitForPort(config.port, 90_000), died]);
    // Port open doesn't mean the app is ready — wait for HTTP 200
    await Promise.race([waitForHttp(`http://127.0.0.1:${config.port}/`, 30_000), died]);
    // Wait for the Atmosphere endpoint to be initialized (servlet may
    // start after the web server is ready, especially on slow CI runners)
    if (config.readyPath) {
      await Promise.race([
        waitForHttp(`http://127.0.0.1:${config.port}${config.readyPath}`, 30_000), died]);
      if (!config.httpOnlyReady) {
        // Verify the WebSocket layer is fully initialized (not just HTTP)
        const wsUrl = `ws://127.0.0.1:${config.port}${config.readyPath}`;
        await Promise.race([waitForWebSocket(wsUrl, 15_000), died]);
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
