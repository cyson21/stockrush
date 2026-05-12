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
      '/orders': {
        target: 'http://localhost:18083',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/orders/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setupTests.ts',
  },
});
