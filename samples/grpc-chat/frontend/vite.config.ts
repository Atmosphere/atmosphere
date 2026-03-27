import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/webapp',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/org.atmosphere.grpc.AtmosphereService': 'http://localhost:8080',
    },
  },
});
