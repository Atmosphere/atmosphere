import { defineConfig } from 'tsup';

export default defineConfig({
  entry: {
    index: 'src/index.ts',
    react: 'src/hooks/react/index.ts',
    vue: 'src/hooks/vue/index.ts',
    svelte: 'src/hooks/svelte/index.ts',
  },
  format: ['esm', 'cjs'],
  dts: true,
  splitting: false,
  sourcemap: true,
  clean: true,
  minify: true,
  outDir: 'dist',
  target: 'es2020',
  platform: 'browser',
  treeshake: true,
  external: ['react', 'vue', 'svelte'],
});
