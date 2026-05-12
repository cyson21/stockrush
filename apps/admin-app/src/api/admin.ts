import { apiUrl, request } from './client';
import type { AdminOrderPage, AdminOrderSaga, OutboxEventPage, OutboxRetryResult } from '../types/admin';

export type ServiceDomain = 'order' | 'inventory' | 'payment';

export function listRecentOrders(): Promise<AdminOrderPage> {
  return request<AdminOrderPage>(apiUrl('order', '/api/admin/orders', { page: '0', size: '20' }));
}

export function getOrderSaga(orderId: string): Promise<AdminOrderSaga> {
  return request<AdminOrderSaga>(apiUrl('order', `/api/admin/orders/${encodeURIComponent(orderId)}/saga`));
}

export function listOutbox(service: ServiceDomain): Promise<OutboxEventPage> {
  return request<OutboxEventPage>(
    apiUrl(service, '/api/admin/outbox-events', {
      status: 'PENDING,FAILED',
      limit: '50',
      offset: '0',
    }),
  );
}

export function retryOutbox(service: ServiceDomain, batchSize = 10): Promise<OutboxRetryResult> {
  return request<OutboxRetryResult>(
    apiUrl(service, '/api/admin/outbox-events/retry', {
      batchSize: String(batchSize),
    }),
    { method: 'POST' },
  );
}
