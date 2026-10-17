import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

const { version } = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as {
  version: string;
};

export default defineConfig({
  // Same constant tsup.config.ts injects into the bundles: src/version.ts
  // reads it, so the tests see the package.json version the build ships.
  define: { __ATMOSPHERE_VERSION__: JSON.stringify(version) },
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
    setupFiles: ['./tests/setup/clear-session-storage.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      exclude: ['tests/**', '**/*.test.ts', '**/*.spec.ts', 'dist/**'],
      include: ['src/**/*.{ts,tsx}'],
    },
    globals: true,
  },
});
