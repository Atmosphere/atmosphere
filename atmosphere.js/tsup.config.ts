import { defineConfig } from 'tsup';

export default defineConfig([
  {
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
  },
  {
    entry: { atmosphere: 'src/index.ts' },
    format: ['iife'],
    globalName: 'AtmosphereJS',
    splitting: false,
    sourcemap: true,
    clean: false,
    minify: true,
    outDir: 'dist',
    target: 'es2020',
    platform: 'browser',
    treeshake: true,
    onSuccess: 'node -e "const fs=require(\'fs\');const f=\'dist/atmosphere.global.js\';let c=fs.readFileSync(f,\'utf8\');c+=\'\\nif(typeof window!==\"undefined\"){window.atmosphere=AtmosphereJS.atmosphere;window.Atmosphere=AtmosphereJS.Atmosphere;window.AtmosphereRooms=AtmosphereJS.AtmosphereRooms;window.subscribeStreaming=AtmosphereJS.subscribeStreaming;}\\n\';fs.writeFileSync(f,c);"',
  },
]);
