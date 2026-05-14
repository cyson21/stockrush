import { apiUrl, makeIdempotencyKey, request } from './client';
import type { CreateOrderRequest, CreateOrderResponse, OrderDetail } from '../types/api';

export function createOrder(payload: CreateOrderRequest): Promise<CreateOrderResponse> {
  return request<CreateOrderResponse>(apiUrl('/api/orders'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': makeIdempotencyKey('order-create'),
      'X-Correlation-Id': 'mobile-order-create',
    },
    body: JSON.stringify(payload),
  });
}

export function getOrder(orderId: string): Promise<OrderDetail> {
  return request<OrderDetail>(apiUrl(`/api/orders/${encodeURIComponent(orderId)}`), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': `mobile-order-${orderId}`,
    },
  });
}
