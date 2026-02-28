import { defineConfig } from '@playwright/test';

/**
 * Playwright configuration for Atmosphere E2E tests.
 *
 * Each sample application runs on a specific port. Tests that share port 8080
 * are configured as serial projects to avoid conflicts.
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
  ],
});
