import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    // Forks pool gives process-level isolation between test files. The
    // default threads pool reuses workers, so module-level state from
    // fetch/EventSource/WebSocket polyfills can leak across file
    // boundaries and trigger order-dependent failures (observed on
    // webtransport.test.ts when new websocket-reconnect tests land).
    pool: 'forks',
    isolate: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      exclude: ['tests/**', '**/*.test.ts', '**/*.spec.ts', 'dist/**'],
      include: ['src/**/*.{ts,tsx}'],
    },
    globals: true,
  },
});
