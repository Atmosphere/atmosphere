import { defineConfig, devices } from '@playwright/test';

/** Cross-browser specs run on Firefox/WebKit when E2E_ALL_BROWSERS is set. */
const crossBrowserSpecs = /\/(chat|multi-client|sse-transport|long-polling-transport|transport-fallback)\.spec\.ts/;

/**
 * Smoke / Deep CI split:
 *   - Tag critical tests with @smoke in their title (e.g. test('@smoke basic chat …'))
 *   - Set SMOKE_ONLY=true to run only @smoke tests (used for PR CI)
 *   - Full suite runs on push-to-main and nightly
 *
 * Flaky test exclusion:
 *   - Tag known-flaky tests with @flaky in their title
 *   - Set INCLUDE_FLAKY=false to exclude them from CI runs
 */
const grepFilter = process.env.SMOKE_ONLY === 'true' ? /@smoke/ : undefined;
const grepInvert = process.env.INCLUDE_FLAKY === 'false' ? /@flaky/ : undefined;

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
  timeout: 90_000,
  expect: { timeout: process.env.CI ? 15_000 : 10_000 },
  fullyParallel: false,
  retries: 1,
  grep: grepFilter,
  grepInvert: grepInvert,
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
      name: 'ai-tools',
      testMatch: /ai-tools\.spec\.ts/,
    },
    {
      name: 'rag-chat',
      testMatch: /rag-chat\.spec\.ts/,
    },
    {
      name: 'a2a-agent',
      testMatch: /a2a-agent\.spec\.ts/,
    },
    {
      name: 'agui-chat',
      testMatch: /agui-chat\.spec\.ts/,
    },
    {
      name: 'multi-agent-startup-team',
      testMatch: /multi-agent-startup-team\.spec\.ts/,
    },
    {
      name: 'dentist-agent',
      testMatch: /dentist-agent\.spec\.ts/,
    },
    {
      name: 'channels-chat',
      testMatch: /channels-chat\.spec\.ts/,
    },
    {
      name: 'rooms-api',
      testMatch: /rooms-api\.spec\.ts/,
    },
    {
      name: 'ai-session-stats',
      testMatch: /ai-session-stats\.spec\.ts/,
    },
    {
      name: 'ai-error-recovery',
      testMatch: /ai-error-recovery\.spec\.ts/,
    },
    {
      name: 'ai-events',
      testMatch: /ai-events\.spec\.ts/,
    },
    {
      name: 'ai-identity',
      testMatch: /ai-identity\.spec\.ts/,
    },
    {
      name: 'ai-memory-strategies',
      testMatch: /ai-memory-strategies\.spec\.ts/,
    },
    {
      name: 'ai-chat-features',
      testMatch: /ai-chat-features\.spec\.ts/,
    },
    {
      name: 'unified-console',
      testMatch: /unified-console\.spec\.ts/,
    },
    {
      name: 'mcp-tools',
      testMatch: /mcp-tools\.spec\.ts/,
    },
    {
      name: 'chat-observability',
      testMatch: /chat-observability\.spec\.ts/,
    },
    {
      name: 'durable-session-token',
      testMatch: /durable-session-token\.spec\.ts/,
    },
    {
      name: 'room-typing-direct',
      testMatch: /room-typing-direct\.spec\.ts/,
    },
    // ── Transport tests ──
    {
      name: 'webtransport',
      testMatch: /webtransport\.spec\.ts/,
      use: {
        // WebTransport with self-signed certs requires serverCertificateHashes
        // which is only supported in real Chrome, not Playwright's bundled Chromium
        channel: 'chrome',
      },
    },
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
    // ── Admin Control Plane ──
    {
      name: 'admin-dashboard',
      testMatch: /admin-dashboard\.spec\.ts/,
    },
    {
      name: 'admin-quarkus',
      testMatch: /admin-quarkus\.spec\.ts/,
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
      name: 'auth-token',
      testMatch: /auth-token\.spec\.ts/,
    },
    {
      name: 'ai-streaming-dom',
      testMatch: /ai-streaming-dom\.spec\.ts/,
    },
    {
      name: 'offline-queue',
      testMatch: /offline-queue\.spec\.ts/,
    },
    {
      name: 'history-cache',
      testMatch: /history-cache\.spec\.ts/,
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
    // ── SQE gist coverage ──
    {
      name: 'console-http-check',
      testMatch: /console-http-check\.spec\.ts/,
    },
    {
      name: 'a2a-discovery',
      testMatch: /a2a-discovery\.spec\.ts/,
    },
    {
      name: 'sample-matrix-smoke',
      testMatch: /sample-matrix-smoke\.spec\.ts/,
    },
    {
      name: 'koog-chat',
      testMatch: /koog-chat\.spec\.ts/,
    },
    // ── P0: Gap analysis coverage ──
    {
      name: 'redis-clustering',
      testMatch: /redis-clustering\.spec\.ts/,
    },
    {
      name: 'kafka-clustering',
      testMatch: /kafka-clustering\.spec\.ts/,
    },
    {
      name: 'kotlin-dsl',
      testMatch: /kotlin-dsl\.spec\.ts/,
    },
    {
      name: 'wasync-client',
      testMatch: /wasync-client\.spec\.ts/,
    },
    {
      name: 'cross-transport-interop',
      testMatch: /cross-transport-interop\.spec\.ts/,
    },
    // ── P1: Gap analysis coverage ──
    {
      name: 'mcp-bidirectional',
      testMatch: /mcp-bidirectional\.spec\.ts/,
    },
    {
      name: 'a2a-multi-hop',
      testMatch: /a2a-multi-hop\.spec\.ts/,
    },
    {
      name: 'coordinator-remote',
      testMatch: /coordinator-remote\.spec\.ts/,
    },
    {
      name: 'durable-session-restart',
      testMatch: /durable-session-restart\.spec\.ts/,
    },
    {
      name: 'agui-sse-lifecycle',
      testMatch: /agui-sse-lifecycle\.spec\.ts/,
    },
    {
      name: 'spring-boot3-parity',
      testMatch: /spring-boot3-parity\.spec\.ts/,
    },
    // ── P2: Gap analysis coverage ──
    {
      name: 'concurrent-protocol-access',
      testMatch: /concurrent-protocol-access\.spec\.ts/,
    },
    {
      name: 'coordinator-journal',
      testMatch: /coordinator-journal\.spec\.ts/,
    },
    {
      name: 'coordinator-activity',
      testMatch: /coordinator-activity\.spec\.ts/,
    },
    {
      name: 'auth-oauth-jwt',
      testMatch: /auth-oauth-jwt\.spec\.ts/,
    },
    {
      name: 'otel-span-correlation',
      testMatch: /otel-span-correlation\.spec\.ts/,
    },
    {
      name: 'channel-gateway',
      testMatch: /channel-gateway\.spec\.ts/,
    },
    {
      name: 'backpressure-bounded-queue',
      testMatch: /backpressure-bounded-queue\.spec\.ts/,
    },
    {
      name: 'webtransport-raw',
      testMatch: /webtransport-raw\.spec\.ts/,
    },
    {
      name: 'session-token-expiry',
      testMatch: /session-token-expiry\.spec\.ts/,
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
