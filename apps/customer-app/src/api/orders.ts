import { apiUrl, makeIdempotencyKey, requestWithAuth } from './client';
import type { CreateOrderRequest, CreateOrderResponse, OrderDetail } from '../types/api';

export function createOrder(payload: CreateOrderRequest): Promise<CreateOrderResponse> {
  return requestWithAuth<CreateOrderResponse>(apiUrl('orders', '/api/orders'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': makeIdempotencyKey(),
      'X-Correlation-Id': 'customer-app-order-create',
    },
    body: JSON.stringify(payload),
  });
}

export function getOrder(orderId: string): Promise<OrderDetail> {
  return requestWithAuth<OrderDetail>(apiUrl('orders', `/api/orders/${encodeURIComponent(orderId)}`), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': `customer-app-order-${orderId}`,
    },
  });
}
