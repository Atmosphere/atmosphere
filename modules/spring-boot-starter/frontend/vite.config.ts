import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/atmosphere/console/',
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
})
