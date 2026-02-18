import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/webapp/',
    emptyOutDir: false,
  },
  server: {
    proxy: {
      '/chat': 'http://localhost:8080',
    },
  },
});
