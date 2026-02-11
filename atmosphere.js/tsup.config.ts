import { defineConfig } from 'tsup';

export default defineConfig({
  entry: ['src/index.ts'],
  format: ['esm', 'cjs', 'iife'],
  dts: true,
  splitting: false,
  sourcemap: true,
  clean: true,
  minify: true,
  globalName: 'atmosphere',
  outDir: 'dist',
  target: 'es2020',
  platform: 'browser',
  treeshake: true,
  outExtension({ format }) {
    return {
      js: format === 'iife' ? '.global.js' : format === 'cjs' ? '.cjs' : '.js',
    };
  },
});
