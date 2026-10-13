// Replaced at build time with the "version" field of package.json: by the
// `define` of every config in tsup.config.ts, and by vitest.config.ts for the
// tests. It is sent to the server as X-Atmosphere-Framework, so it must never
// be a hand-maintained literal — one drifted to '5.0.7' while the package
// shipped as 5.0.47.
declare const __ATMOSPHERE_VERSION__: string;

export const VERSION: string = __ATMOSPHERE_VERSION__;
