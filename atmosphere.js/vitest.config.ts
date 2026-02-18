import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'lcov'],
      exclude: ['tests/**', '**/*.test.ts', '**/*.spec.ts', 'dist/**'],
      include: ['src/**/*.{ts,tsx}'],
    },
    globals: true,
  },
});
