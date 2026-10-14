import { defineConfig, searchForWorkspaceRoot } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/atmosphere/console/',
  // Stamp a literal nonce placeholder onto the <meta property="csp-nonce">, the
  // entry <script>, and the <link rel="stylesheet"> tags. The server
  // (ConsoleResourceFilter / AtmosphereConsoleServlet) substitutes a fresh
  // per-request value and matches it in the Content-Security-Policy header, so
  // the console runs under a nonce-based strict CSP with no 'unsafe-inline'.
  // NEVER ship this sentinel as-is — a reused nonce is equivalent to unsafe-inline.
  html: { cspNonce: '__ATMO_CSP_NONCE__' },
  build: {
    // Emit straight into the Maven build output (target/classes) so the console
    // is packaged into the JAR's classpath at
    // META-INF/resources/atmosphere/console/ — the path the ResourceHandler
    // serves from. The bundle is a build artifact regenerated from the canonical
    // atmosphere.js on every Maven build; it is NEVER committed (target/ is
    // gitignored), so a stale committed bundle can never drift onto the wire.
    outDir: '../target/classes/META-INF/resources/atmosphere/console',
    emptyOutDir: true,
  },
  server: {
    fs: {
      allow: [
        searchForWorkspaceRoot(process.cwd()),
        // src/adminDashboard.test.ts loads each host's copy of the standalone
        // admin dashboard page (?raw) to run it under jsdom. Dev/test only.
        '../src/main/resources/META-INF/resources/atmosphere/admin',
        '../../spring-boot3-starter/src/main/resources/META-INF/resources/atmosphere/admin',
        '../../quarkus-admin-extension/runtime/src/main/resources/META-INF/resources/admin',
      ],
    },
  },
})
