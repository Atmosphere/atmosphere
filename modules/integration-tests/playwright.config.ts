import { defineConfig, devices } from '@playwright/test';

/** Cross-browser specs run on Firefox/WebKit when E2E_ALL_BROWSERS is set. */
const crossBrowserSpecs = /\/(chat|multi-client|sse-transport|long-polling-transport|transport-fallback)\.spec\.ts/;

/**
 * Playwright configuration for Atmosphere E2E tests.
 *
 * Each sample application runs on a specific port. Tests that share port 8080
 * are configured as serial projects to avoid conflicts.
 *
 * Set E2E_ALL_BROWSERS=true to also run a subset of specs on Firefox and WebKit.
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 1,
  workers: 1,
  reporter: [['html', { open: 'never' }], ['list']],

  use: {
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
  },

  projects: [
    {
      name: 'chat',
      testMatch: /\/chat\.spec\.ts/,
    },
    {
      name: 'spring-boot-chat',
      testMatch: /spring-boot-chat\.spec\.ts/,
    },
    {
      name: 'multi-client',
      testMatch: /multi-client\.spec\.ts/,
    },
    {
      name: 'embedded-jetty-chat',
      testMatch: /embedded-jetty-chat\.spec\.ts/,
    },
    {
      name: 'quarkus-chat',
      testMatch: /quarkus-chat\.spec\.ts/,
    },
    {
      name: 'ai-chat',
      testMatch: /spring-boot-ai-chat\.spec\.ts/,
    },
    {
      name: 'langchain4j-chat',
      testMatch: /langchain4j-chat\.spec\.ts/,
    },
    {
      name: 'embabel-chat',
      testMatch: /embabel-chat\.spec\.ts/,
    },
    {
      name: 'spring-ai-chat',
      testMatch: /spring-ai-chat\.spec\.ts/,
    },
    {
      name: 'durable-sessions',
      testMatch: /durable-sessions\.spec\.ts/,
    },
    {
      name: 'mcp-server',
      testMatch: /mcp-server\.spec\.ts/,
    },
    {
      name: 'grpc-browser',
      testMatch: /grpc-browser\.spec\.ts/,
    },
    {
      name: 'ai-filters',
      testMatch: /ai-filters\.spec\.ts/,
    },
    {
      name: 'ai-fanout',
      testMatch: /ai-fanout\.spec\.ts/,
    },
    {
      name: 'ai-cache',
      testMatch: /ai-cache\.spec\.ts/,
    },
    {
      name: 'ai-routing',
      testMatch: /ai-routing\.spec\.ts/,
    },
    {
      name: 'ai-budget',
      testMatch: /ai-budget\.spec\.ts/,
    },
    {
      name: 'ai-cache-coalescing',
      testMatch: /ai-cache-coalescing\.spec\.ts/,
    },
    {
      name: 'ai-cost-routing',
      testMatch: /ai-cost-routing\.spec\.ts/,
    },
    {
      name: 'ai-combined-cost-cache',
      testMatch: /ai-combined-cost-cache\.spec\.ts/,
    },
    {
      name: 'ai-classroom',
      testMatch: /ai-classroom\.spec\.ts/,
    },
    {
      name: 'spring-boot-ai-classroom',
      testMatch: /spring-boot-ai-classroom\.spec\.ts/,
    },
    {
      name: 'ai-memory',
      testMatch: /ai-memory\.spec\.ts/,
    },
    {
      name: 'langchain4j-tools',
      testMatch: /langchain4j-tools\.spec\.ts/,
    },
    {
      name: 'adk-chat',
      testMatch: /adk-chat\.spec\.ts/,
    },
    {
      name: 'adk-tools',
      testMatch: /adk-tools\.spec\.ts/,
    },
    {
      name: 'spring-ai-routing',
      testMatch: /spring-ai-routing\.spec\.ts/,
    },
    {
      name: 'embabel-horoscope',
      testMatch: /embabel-horoscope\.spec\.ts/,
    },
    {
      name: 'ai-tools',
      testMatch: /ai-tools\.spec\.ts/,
    },
    // ── Transport tests ──
    {
      name: 'sse-transport',
      testMatch: /sse-transport\.spec\.ts/,
    },
    {
      name: 'long-polling-transport',
      testMatch: /long-polling-transport\.spec\.ts/,
    },
    {
      name: 'transport-fallback',
      testMatch: /transport-fallback\.spec\.ts/,
    },
    {
      name: 'reconnection',
      testMatch: /reconnection\.spec\.ts/,
    },
    // ── P1: Coverage gaps ──
    {
      name: 'otel-chat',
      testMatch: /otel-chat\.spec\.ts/,
    },
    {
      name: 'xss-protection',
      testMatch: /xss-protection\.spec\.ts/,
    },
    {
      name: 'auth-rejection',
      testMatch: /auth-rejection\.spec\.ts/,
    },
    {
      name: 'ai-streaming-dom',
      testMatch: /ai-streaming-dom\.spec\.ts/,
    },
    // ── P2: Deeper coverage ──
    {
      name: 'message-ordering',
      testMatch: /message-ordering\.spec\.ts/,
    },
    {
      name: 'large-payload',
      testMatch: /large-payload\.spec\.ts/,
    },
    {
      name: 'slow-consumer',
      testMatch: /slow-consumer\.spec\.ts/,
    },
    {
      name: 'connection-timeout',
      testMatch: /connection-timeout\.spec\.ts/,
    },
    {
      name: 'durable-session-identity',
      testMatch: /durable-session-identity\.spec\.ts/,
    },
    // ── Cross-browser (opt-in via E2E_ALL_BROWSERS=true) ──
    ...(process.env.E2E_ALL_BROWSERS ? [
      {
        name: 'firefox',
        testMatch: crossBrowserSpecs,
        use: { ...devices['Desktop Firefox'] },
      },
      {
        name: 'webkit',
        testMatch: crossBrowserSpecs,
        use: { ...devices['Desktop Safari'] },
      },
    ] : []),
  ],
});
