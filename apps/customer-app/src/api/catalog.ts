import { apiUrl, request } from './client';
import type { Product } from '../types/api';

export function listOnSaleProducts(query?: string): Promise<Product[]> {
  const searchQuery = query?.trim();
  const params: Record<string, string> = {
    status: 'ON_SALE',
  };

  if (searchQuery) {
    params.q = searchQuery;
  }

  return request<Product[]>(apiUrl('catalog', '/api/products', params), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': 'customer-app-catalog-list',
    },
  });
}
