import { apiUrl, request } from './client';
import { withAuthHeader } from '../auth/http';
import type { OrderSummary, PageResponse } from '../types/api';

export async function listOrderHistory(
  memberId: string,
  page = 0,
  size = 20,
  accessToken?: string | null,
): Promise<PageResponse<OrderSummary>> {
  const headers = await withAuthHeader({
    'X-Correlation-Id': `mobile-read-model-${memberId}`,
  }, accessToken);

  return request<PageResponse<OrderSummary>>(apiUrl('/api/read-model/orders', { memberId, page, size }), {
    method: 'GET',
    headers,
  });
}
