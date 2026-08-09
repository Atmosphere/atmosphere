import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  resolve: {
    alias: {
      // `react-native` is an optional peer dependency and is not installed
      // here, so the RN hooks could not be imported by a test at all. The
      // stub provides the handful of names they import (AppState, View,
      // Text, StyleSheet) so `src/hooks/react-native/**` can be exercised
      // for real instead of asserted against as source text.
      'react-native': fileURLToPath(new URL('./tests/stubs/react-native.ts', import.meta.url)),
    },
  },
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
