// 번들러/개발 서버 설정을 정의해 경로 프록시와 플러그인을 관리합니다.
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api/admin': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/api/admin/outbox-services': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/api/admin/coupon-usages': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/api/admin/fulfillment-requests': {
        target: 'http://localhost:18080',
        changeOrigin: true,
      },
      '/api/read-model': {
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
