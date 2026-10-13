import { readFileSync } from 'node:fs';
import { defineConfig } from 'tsup';

// src/version.ts reads __ATMOSPHERE_VERSION__, which every config below
// replaces with the package.json version so VERSION (sent to the server as
// X-Atmosphere-Framework) follows the release workflow's version bumps.
const { version } = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')) as {
  version: string;
};
const define = { __ATMOSPHERE_VERSION__: JSON.stringify(version) };

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
    // react-markdown and remark-gfm are optional peer dependencies used only by
    // the chat entry; the consumer installs them, the bundle never inlines them.
    external: ['react', 'vue', 'svelte', 'react-markdown', 'remark-gfm'],
    define,
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
    define,
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
    // No rollup pass for the IIFE: tsup's treeshake re-bundles esbuild's output
    // through rollup, which moves the footer below inside the IIFE body (where
    // AtmosphereJS is not assigned yet) and appends a second sourceMappingURL
    // comment. esbuild already tree-shakes while bundling.
    treeshake: false,
    define,
    // Browser globals for <script> users. A footer instead of an onSuccess
    // shell one-liner: shell quoting stripped the quotes around 'undefined',
    // so the guard was always true and threw outside a browser (#2791).
    // esbuild places the footer after the IIFE and before the single
    // sourceMappingURL comment, so the map still lines up.
    footer: {
      js:
        "if(typeof window!=='undefined'){" +
        'window.atmosphere=AtmosphereJS.atmosphere;' +
        'window.Atmosphere=AtmosphereJS.Atmosphere;' +
        'window.AtmosphereRooms=AtmosphereJS.AtmosphereRooms;' +
        'window.subscribeStreaming=AtmosphereJS.subscribeStreaming;' +
        '}',
    },
  },
]);
