import { apiUrl, request } from './client';
import type { Stock } from '../types/api';

export function listStocks(productCode: string): Promise<Stock[]> {
  return request<Stock[]>(apiUrl('inventory', '/api/stocks', { productCode }), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': `customer-app-stock-${productCode}`,
    },
  });
}
