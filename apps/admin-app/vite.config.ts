import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
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
      '/payment': {
        target: 'http://localhost:18084',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/payment/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setupTests.ts',
  },
});
