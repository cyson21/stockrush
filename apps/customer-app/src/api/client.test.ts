import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiUrl } from './client';

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('customer app API URL builder', () => {
  it('builds catalog request URLs against customer web gateway', () => {
    const url = apiUrl('catalog', '/api/products', { status: 'ON_SALE', q: 'Hoodie' });
    expect(url).toBe('/api/products?status=ON_SALE&q=Hoodie');
  });

  it('builds inventory request URLs against customer web gateway', () => {
    const url = apiUrl('inventory', '/api/stocks', { productCode: 'LIMITED-001' });
    expect(url).toBe('/api/stocks?productCode=LIMITED-001');
  });

  it('builds promotion request URLs against customer web gateway', () => {
    const url = apiUrl('promotion', '/api/coupons/quote');
    expect(url).toBe('/api/coupons/quote');
  });

  it('uses one configured gateway base URL for every service facade', () => {
    vi.stubEnv('VITE_API_BASE_URL', 'https://demo.stockrush.local/gateway/');

    expect(apiUrl('catalog', '/api/products', { status: 'ON_SALE' })).toBe(
      'https://demo.stockrush.local/gateway/api/products?status=ON_SALE',
    );
    expect(apiUrl('inventory', '/api/stocks', { productCode: 'LIMITED-001' })).toBe(
      'https://demo.stockrush.local/gateway/api/stocks?productCode=LIMITED-001',
    );
    expect(apiUrl('promotion', '/api/coupons/quote')).toBe(
      'https://demo.stockrush.local/gateway/api/coupons/quote',
    );
  });
});
