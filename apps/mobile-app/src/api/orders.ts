import { apiUrl, makeIdempotencyKey, request } from './client';
import { withAuthHeader } from '../auth/http';
import type { CreateOrderRequest, CreateOrderResponse, OrderDetail } from '../types/api';

export async function createOrder(payload: CreateOrderRequest, accessToken?: string | null): Promise<CreateOrderResponse> {
  const headers = await withAuthHeader({
    'Content-Type': 'application/json',
    'Idempotency-Key': makeIdempotencyKey('order-create'),
    'X-Correlation-Id': 'mobile-order-create',
  }, accessToken);

  return request<CreateOrderResponse>(apiUrl('/api/orders'), {
    method: 'POST',
    headers,
    body: JSON.stringify(payload),
  });
}

export async function getOrder(orderId: string, accessToken?: string | null): Promise<OrderDetail> {
  const headers = await withAuthHeader({
    'X-Correlation-Id': `mobile-order-${orderId}`,
  }, accessToken);

  return request<OrderDetail>(apiUrl(`/api/orders/${encodeURIComponent(orderId)}`), {
    method: 'GET',
    headers,
  });
}
