import { defineConfig } from '@playwright/test';

/**
 * Playwright configuration for Atmosphere E2E tests.
 *
 * Starts the embedded Jetty WebSocket chat sample as the web server,
 * then runs Chromium-based tests against it.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  retries: 0,
  workers: 1, // serialize tests — they share a single chat server

  use: {
    baseURL: 'http://localhost:8080',
    // Collect traces on failure for debugging
    trace: 'on-first-retry',
  },

  projects: [
    {
      name: 'chromium',
      use: {
        browserName: 'chromium',
      },
    },
  ],

  webServer: {
    // Start the embedded Jetty WebSocket chat sample.
    // The Maven exec plugin runs EmbeddedJettyWebSocketChat.main(),
    // which starts Jetty on port 8080 and serves the chat UI.
    command:
      'cd ../samples/embedded-jetty-websocket-chat && ../../mvnw compile exec:java -Dexec.mainClass=org.atmosphere.samples.chat.EmbeddedJettyWebSocketChat',
    url: 'http://localhost:8080',
    // The first build + server start can take a while
    timeout: 120_000,
    reuseExistingServer: !process.env.CI,
    stdout: 'pipe',
    stderr: 'pipe',
  },
});
