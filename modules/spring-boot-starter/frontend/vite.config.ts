import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  base: '/atmosphere/console/',
  build: {
    outDir: '../src/main/resources/META-INF/resources/atmosphere/console',
    emptyOutDir: true,
  },
})
