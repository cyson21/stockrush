import { apiUrl, request } from './client';
import type { Stock } from '../types/api';

export function listStocks(productCode: string): Promise<Stock[]> {
  return request<Stock[]>(apiUrl('/api/stocks', { productCode }), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': `mobile-stock-${productCode}`,
    },
  });
}
