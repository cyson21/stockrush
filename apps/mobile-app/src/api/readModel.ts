import { apiUrl, request } from './client';
import type { OrderSummary, PageResponse } from '../types/api';

export function listOrderHistory(memberId: string, page = 0, size = 20): Promise<PageResponse<OrderSummary>> {
  return request<PageResponse<OrderSummary>>(apiUrl('/api/read-model/orders', { memberId, page, size }), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': `mobile-read-model-${memberId}`,
    },
  });
}
