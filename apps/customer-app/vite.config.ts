import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/catalog': {
        target: 'http://localhost:18081',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/catalog/, ''),
      },
      '/inventory': {
        target: 'http://localhost:18082',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/inventory/, ''),
      },
      '/api/orders': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/promotion': {
        target: 'http://localhost:18085',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/promotion/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setupTests.ts',
  },
});
