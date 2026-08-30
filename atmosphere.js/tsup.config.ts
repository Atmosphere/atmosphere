import { defineConfig } from 'tsup';

export default defineConfig([
  {
    entry: {
      index: 'src/index.ts',
      react: 'src/hooks/react/index.ts',
      vue: 'src/hooks/vue/index.ts',
      svelte: 'src/hooks/svelte/index.ts',
      chat: 'src/chat/index.ts',
      room: 'src/room.ts',
      streaming: 'src/streaming-entry.ts',
      queue: 'src/queue.ts',
      history: 'src/history.ts',
      interactions: 'src/interactions.ts',
    },
    format: ['esm', 'cjs'],
    // Declarations come from scripts/build-dts.mjs (TypeScript 7's own
    // emitter). tsup 8.5.1 cannot emit them under TS 7: its vendored
    // rollup-plugin-dts calls the removed ts.sys.useCaseSensitiveFileNames,
    // and experimentalDts calls the removed parseJsonConfigFileContent.
    dts: false,
    splitting: false,
    sourcemap: true,
    // No config cleans: `npm run build` cleans once, up front. When this config
    // cleaned, it raced the react-native config below and deleted the
    // declarations tsup had just written for it — the build still exited 0 and
    // the package published advertising a .d.ts it did not contain.
    clean: false,
    minify: true,
    outDir: 'dist',
    target: 'es2020',
    platform: 'browser',
    treeshake: true,
    external: ['react', 'vue', 'svelte'],
  },
  {
    entry: {
      'react-native': 'src/hooks/react-native/index.ts',
    },
    format: ['esm', 'cjs'],
    // Declarations come from scripts/build-dts.mjs (TypeScript 7's own
    // emitter). tsup 8.5.1 cannot emit them under TS 7: its vendored
    // rollup-plugin-dts calls the removed ts.sys.useCaseSensitiveFileNames,
    // and experimentalDts calls the removed parseJsonConfigFileContent.
    dts: false,
    splitting: false,
    sourcemap: true,
    clean: false,
    minify: true,
    outDir: 'dist',
    target: 'es2020',
    platform: 'neutral',
    treeshake: true,
    external: ['react', 'react-native', '@react-native-community/netinfo'],
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
