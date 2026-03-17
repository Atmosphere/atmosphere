import { defineConfig } from '@playwright/test';

/**
 * Playwright configuration for Atmosphere E2E tests.
 *
 * Supports multiple sample applications via the SAMPLE environment variable:
 *   - SAMPLE=embedded-jetty-chat  (default) — embedded Jetty WebSocket chat
 *   - SAMPLE=spring-boot-chat     — Spring Boot chat with Room API
 *   - SAMPLE=quarkus-chat         — Quarkus chat
 *   - SAMPLE=mcp-server           — Spring Boot with MCP server
 *   - SAMPLE=ai-chat              — Spring Boot AI chat (streaming)
 *   - SAMPLE=ai-classroom         — Spring Boot AI classroom (multi-room)
 *   - SAMPLE=ai-tools             — Spring Boot AI tools (tool calling)
 *   - SAMPLE=adk-chat             — Spring Boot ADK chat
 *   - SAMPLE=adk-tools            — Spring Boot ADK tools
 *   - SAMPLE=langchain4j-chat     — Spring Boot LangChain4j chat
 *   - SAMPLE=langchain4j-tools    — Spring Boot LangChain4j tools
 *   - SAMPLE=embabel-chat         — Spring Boot Embabel chat
 *   - SAMPLE=embabel-horoscope    — Spring Boot Embabel horoscope
 *   - SAMPLE=spring-ai-chat       — Spring Boot Spring AI chat
 *   - SAMPLE=spring-ai-routing    — Spring Boot Spring AI routing
 *   - SAMPLE=rag-chat             — Spring Boot RAG chat
 *   - SAMPLE=otel-chat            — Spring Boot with OpenTelemetry tracing
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

function springBootRun(sample: string, port: number): string {
  return `cd ../samples/${sample} && ${MVN} spring-boot:run -Dspring-boot.run.arguments="--server.port=${port}"`;
}

const SAMPLES: Record<string, SampleConfig> = {
  // --- Existing broadcast chat samples ---
  'embedded-jetty-chat': {
    command: `cd ../samples/embedded-jetty-websocket-chat && ${MVN} compile exec:java -Dexec.mainClass=org.atmosphere.samples.chat.EmbeddedJettyWebSocketChat`,
    port: 8080,
    testDir: './tests/embedded-jetty-chat',
  },
  'spring-boot-chat': {
    command: springBootRun('spring-boot-chat', 8081),
    port: 8081,
    testDir: './tests/spring-boot-chat',
  },
  'quarkus-chat': {
    command: `cd ../samples/quarkus-chat && ${MVN} quarkus:dev -Dquarkus.http.port=8082`,
    port: 8082,
    testDir: './tests/quarkus-chat',
  },
  'mcp-server': {
    command: springBootRun('spring-boot-mcp-server', 8085),
    port: 8085,
    testDir: './tests/mcp-server',
  },

  // --- AI streaming chat samples ---
  'ai-chat': {
    command: springBootRun('spring-boot-ai-chat', 8083),
    port: 8083,
    testDir: './tests/ai-chat',
  },
  'ai-classroom': {
    command: springBootRun('spring-boot-ai-classroom', 8084),
    port: 8084,
    testDir: './tests/ai-classroom',
  },
  'ai-tools': {
    command: springBootRun('spring-boot-ai-tools', 8086),
    port: 8086,
    testDir: './tests/ai-tools',
  },
  'adk-chat': {
    command: springBootRun('spring-boot-adk-chat', 8087),
    port: 8087,
    testDir: './tests/adk-chat',
  },
  'adk-tools': {
    command: springBootRun('spring-boot-adk-tools', 8088),
    port: 8088,
    testDir: './tests/adk-tools',
  },
  'langchain4j-chat': {
    command: springBootRun('spring-boot-langchain4j-chat', 8089),
    port: 8089,
    testDir: './tests/langchain4j-chat',
  },
  'langchain4j-tools': {
    command: springBootRun('spring-boot-langchain4j-tools', 8090),
    port: 8090,
    testDir: './tests/langchain4j-tools',
  },
  'embabel-chat': {
    command: springBootRun('spring-boot-embabel-chat', 8091),
    port: 8091,
    testDir: './tests/embabel-chat',
  },
  'embabel-horoscope': {
    command: springBootRun('spring-boot-embabel-horoscope', 8092),
    port: 8092,
    testDir: './tests/embabel-horoscope',
  },
  'spring-ai-chat': {
    command: springBootRun('spring-boot-spring-ai-chat', 8093),
    port: 8093,
    testDir: './tests/spring-ai-chat',
  },
  'spring-ai-routing': {
    command: springBootRun('spring-boot-spring-ai-routing', 8094),
    port: 8094,
    testDir: './tests/spring-ai-routing',
  },
  'rag-chat': {
    command: springBootRun('spring-boot-rag-chat', 8095),
    port: 8095,
    testDir: './tests/rag-chat',
  },
  'otel-chat': {
    command: springBootRun('spring-boot-otel-chat', 8096),
    port: 8096,
    testDir: './tests/otel-chat',
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
