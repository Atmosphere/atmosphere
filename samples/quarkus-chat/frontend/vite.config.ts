import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  resolve: {
    dedupe: ['react', 'react-dom'],
  },
  build: {
    outDir: '../src/main/resources/META-INF/resources/',
    emptyOutDir: false,
  },
  server: {
    proxy: {
      '/atmosphere': 'http://localhost:8080',
    },
  },
});
