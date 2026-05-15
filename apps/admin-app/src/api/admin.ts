import { apiUrl, gatewayApiUrl, request } from './client';
import type {
  AdminOrderPage,
  AdminOrderCancelResult,
  AdminOrderSaga,
  CatalogProduct,
  CouponUsagePage,
  FulfillmentRequestPage,
  OutboxEventPage,
  OutboxRequeueResult,
  OutboxRetryResult,
  ProductCreatePayload,
  ProductUpdatePayload,
  ReadModelOrderPage,
  StockItem,
  StockSetPayload,
} from '../types/admin';

export type ServiceDomain = 'order' | 'inventory' | 'payment';

export type ReadModelOrderFilters = {
  orderId?: string;
  memberId?: string;
  status?: string;
  sagaStatus?: string;
  couponCode?: string;
};

export function listRecentOrders(): Promise<AdminOrderPage> {
  return request<AdminOrderPage>(apiUrl('order', '/api/admin/orders', { page: '0', size: '20' }));
}

export function listReadModelAdminOrders(filters: ReadModelOrderFilters = {}): Promise<ReadModelOrderPage> {
  const params: Record<string, string> = {
    page: '0',
    size: '50',
  };
  if (filters.orderId?.trim()) {
    params.orderId = filters.orderId.trim();
  }
  if (filters.memberId?.trim()) {
    params.memberId = filters.memberId.trim();
  }
  if (filters.status?.trim()) {
    params.status = filters.status.trim();
  }
  if (filters.sagaStatus?.trim()) {
    params.sagaStatus = filters.sagaStatus.trim();
  }
  if (filters.couponCode?.trim()) {
    params.couponCode = filters.couponCode.trim();
  }

  return request<ReadModelOrderPage>(gatewayApiUrl('/api/read-model/admin/orders', params));
}

export function listCouponUsages(filters: {
  couponCode?: string;
  memberId?: string;
  status?: string;
} = {}): Promise<CouponUsagePage> {
  const params: Record<string, string> = {
    page: '0',
    size: '50',
  };
  if (filters.couponCode?.trim()) {
    params.couponCode = filters.couponCode.trim();
  }
  if (filters.memberId?.trim()) {
    params.memberId = filters.memberId.trim();
  }
  if (filters.status?.trim()) {
    params.status = filters.status.trim();
  }

  return request<CouponUsagePage>(gatewayApiUrl('/api/admin/coupon-usages', params));
}

export function listFulfillmentRequests(filters: {
  orderId?: string;
  status?: string;
} = {}): Promise<FulfillmentRequestPage> {
  const params: Record<string, string> = {
    page: '0',
    size: '50',
  };
  if (filters.orderId?.trim()) {
    params.orderId = filters.orderId.trim();
  }
  if (filters.status?.trim()) {
    params.status = filters.status.trim();
  }

  return request<FulfillmentRequestPage>(gatewayApiUrl('/api/admin/fulfillment-requests', params));
}

export function getOrderSaga(orderId: string): Promise<AdminOrderSaga> {
  return request<AdminOrderSaga>(apiUrl('order', `/api/admin/orders/${encodeURIComponent(orderId)}/saga`));
}

export function cancelDelayedOrder(orderId: string, idempotencyKey: string): Promise<AdminOrderCancelResult> {
  return request<AdminOrderCancelResult>(apiUrl('order', `/api/admin/orders/${encodeURIComponent(orderId)}/cancel`), {
    method: 'POST',
    headers: {
      'Idempotency-Key': idempotencyKey,
    },
  });
}

export function listCatalogProducts(status: string): Promise<CatalogProduct[]> {
  return request<CatalogProduct[]>(apiUrl('catalog', '/api/products', { status }));
}

export function createCatalogProduct(payload: ProductCreatePayload, idempotencyKey: string): Promise<CatalogProduct> {
  return request<CatalogProduct>(apiUrl('catalog', '/api/admin/products'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify(payload),
  });
}

export function updateCatalogProduct(
  productCode: string,
  payload: ProductUpdatePayload,
  idempotencyKey: string,
): Promise<CatalogProduct> {
  return request<CatalogProduct>(apiUrl('catalog', `/api/admin/products/${encodeURIComponent(productCode)}`), {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    body: JSON.stringify(payload),
  });
}

export function listStocksByProductCode(productCode: string): Promise<StockItem[]> {
  return request<StockItem[]>(apiUrl('inventory', '/api/stocks', { productCode }));
}

export function setStockQuantity(skuId: string, payload: StockSetPayload): Promise<StockItem> {
  return request<StockItem>(apiUrl('inventory', `/api/stocks/${encodeURIComponent(skuId)}`), {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(payload),
  });
}

export function listOutbox(service: ServiceDomain): Promise<OutboxEventPage> {
  return request<OutboxEventPage>(
    gatewayApiUrl(`/api/admin/outbox-services/${service}/events`, {
      status: 'PENDING,FAILED',
      limit: '50',
      offset: '0',
    }),
  );
}

export function retryOutbox(service: ServiceDomain, batchSize = 10): Promise<OutboxRetryResult> {
  return request<OutboxRetryResult>(
    gatewayApiUrl(`/api/admin/outbox-services/${service}/events/retry`, {
      batchSize: String(batchSize),
    }),
    {
      method: 'POST',
    },
  );
}

export function requeueFailedOutbox(service: ServiceDomain, batchSize = 10): Promise<OutboxRequeueResult> {
  return request<OutboxRequeueResult>(
    gatewayApiUrl(`/api/admin/outbox-services/${service}/events/failed/requeue`, {
      batchSize: String(batchSize),
    }),
    {
      method: 'POST',
    },
  );
}
