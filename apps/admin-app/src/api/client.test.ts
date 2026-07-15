// admin-app API 클라이언트의 Gateway 경로 고정을 검증합니다.
import { afterEach, describe, expect, it, vi } from 'vitest';
import * as client from './client';

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('admin app API URL builder', () => {
  it('builds protected admin URLs against the Gateway base URL', () => {
    vi.stubEnv('VITE_GATEWAY_API_BASE_URL', 'https://demo.stockrush.local/gateway/');

    expect(client.gatewayApiUrl('/api/admin/orders', { page: '0', size: '20' })).toBe(
      'https://demo.stockrush.local/gateway/api/admin/orders?page=0&size=20',
    );
    expect(client.gatewayApiUrl('/api/read-model/admin/orders', { memberId: 'member-a' })).toBe(
      'https://demo.stockrush.local/gateway/api/read-model/admin/orders?memberId=member-a',
    );
  });

  it('does not expose service-local URL helpers from runtime code', () => {
    expect('apiUrl' in client).toBe(false);
  });
});
