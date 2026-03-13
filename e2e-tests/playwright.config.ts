import { defineConfig } from '@playwright/test';

/**
 * Playwright configuration for Atmosphere E2E tests.
 *
 * Supports multiple sample applications via the SAMPLE environment variable:
 *   - SAMPLE=embedded-jetty-chat  (default) — embedded Jetty WebSocket chat
 *   - SAMPLE=spring-boot-chat     — Spring Boot chat with Room API
 *   - SAMPLE=quarkus-chat         — Quarkus chat
 *   - SAMPLE=durable-sessions     — Spring Boot with SQLite session persistence
 *   - SAMPLE=otel-chat            — Spring Boot with OpenTelemetry tracing
 *   - SAMPLE=mcp-server           — Spring Boot with MCP server
 *   - SAMPLE=all                  — run all samples sequentially
 *
 * Examples:
 *   npx playwright test                              # embedded-jetty-chat only
 *   SAMPLE=spring-boot-chat npx playwright test      # spring-boot-chat only
 *   SAMPLE=all npx playwright test                   # all samples
 */

// ---------------------------------------------------------------------------
// Sample registry: maps sample names to their startup config
// ---------------------------------------------------------------------------

interface SampleConfig {
  /** Maven command to start the sample (run from e2e-tests/ directory) */
  command: string;
  /** Port the sample listens on */
  port: number;
  /** Test directory relative to tests/ */
  testDir: string;
}

const MVN = '../../mvnw -ntp';

const SAMPLES: Record<string, SampleConfig> = {
  'embedded-jetty-chat': {
    command: `cd ../samples/embedded-jetty-websocket-chat && ${MVN} compile exec:java -Dexec.mainClass=org.atmosphere.samples.chat.EmbeddedJettyWebSocketChat`,
    port: 8080,
    testDir: './tests/embedded-jetty-chat',
  },
  'spring-boot-chat': {
    command: `cd ../samples/spring-boot-chat && ${MVN} spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"`,
    port: 8081,
    testDir: './tests/spring-boot-chat',
  },
  'quarkus-chat': {
    command: `cd ../samples/quarkus-chat && ${MVN} quarkus:dev -Dquarkus.http.port=8082`,
    port: 8082,
    testDir: './tests/quarkus-chat',
  },
  'mcp-server': {
    command: `cd ../samples/spring-boot-mcp-server && ${MVN} spring-boot:run -Dspring-boot.run.arguments="--server.port=8085"`,
    port: 8085,
    testDir: './tests/mcp-server',
  },
};

// ---------------------------------------------------------------------------
// Resolve which samples to run based on SAMPLE env var
// ---------------------------------------------------------------------------

const sampleEnv = process.env.SAMPLE || 'embedded-jetty-chat';

const activeSamples: [string, SampleConfig][] =
  sampleEnv === 'all'
    ? Object.entries(SAMPLES)
    : [[sampleEnv, SAMPLES[sampleEnv]]];

if (!activeSamples.every(([, config]) => config)) {
  const valid = Object.keys(SAMPLES).join(', ');
  throw new Error(
    `Unknown SAMPLE="${sampleEnv}". Valid values: ${valid}, all`,
  );
}

// ---------------------------------------------------------------------------
// Playwright config
// ---------------------------------------------------------------------------

export default defineConfig({
  timeout: 30_000,
  retries: 0,
  workers: 1, // serialize tests — they share a single chat server

  projects: activeSamples.map(([name, config]) => ({
    name,
    testDir: config.testDir,
    use: {
      browserName: 'chromium' as const,
      baseURL: `http://localhost:${config.port}`,
      trace: 'on-first-retry' as const,
    },
  })),

  webServer: activeSamples.map(([, config]) => ({
    command: config.command,
    url: `http://localhost:${config.port}`,
    timeout: 120_000,
    reuseExistingServer: !process.env.CI,
    stdout: 'pipe' as const,
    stderr: 'pipe' as const,
  })),
});
