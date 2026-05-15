import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api/products': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/api/stocks': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/api/orders': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/api/coupons': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setupTests.ts',
  },
});
