import { apiUrl, request } from './client';
import type { Product } from '../types/api';

export function listOnSaleProducts(): Promise<Product[]> {
  return request<Product[]>(apiUrl('/api/products', { status: 'ON_SALE' }), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': 'mobile-catalog-list',
    },
  });
}
