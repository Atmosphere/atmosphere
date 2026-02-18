import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static/',
    emptyOutDir: false,
  },
  server: {
    proxy: {
      '/atmosphere': 'http://localhost:8080',
      '/api': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
});
