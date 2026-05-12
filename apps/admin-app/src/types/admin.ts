export type ApiError = {
  code: string;
  message: string;
  details: Record<string, unknown>;
};

export type ApiResponse<T> = {
  success: boolean;
  data: T | null;
  error: ApiError | null;
  trace: {
    correlationId: string;
  };
};

export type AdminOrderSummary = {
  orderId: string;
  memberId: string;
  status: string;
  sagaStatus: string;
  paymentMethod: string;
  totalAmount: number;
  itemCount: number;
  createdAt: string;
  updatedAt: string;
};

export type AdminOrderPage = {
  page: number;
  size: number;
  items: AdminOrderSummary[];
};

export type AdminOrderSaga = {
  orderId: string;
  orderStatus: string;
  sagaStatus: string;
  failedAt: string | null;
  businessReason: string | null;
  technicalErrorMessage: string | null;
  lastEventType: string | null;
  outboxAttempts: number;
};

export type OutboxEvent = {
  eventId: string;
  aggregateType: string;
  aggregateId: string;
  eventType: string;
  status: string;
  retryCount: number;
  maxRetryCount: number;
  nextRetryAt: string | null;
  errorMessage: string | null;
  createdAt: string;
  publishedAt: string | null;
};

export type OutboxEventPage = {
  limit: number;
  offset: number;
  items: OutboxEvent[];
};

export type OutboxRetryResult = {
  claimed: number;
  published: number;
  failed: number;
};

export type SalesStatus = 'ON_SALE' | 'STOPPED';

export type CatalogProduct = {
  productCode: string;
  name: string;
  status: SalesStatus | string;
  listPrice: number;
};

export type ProductCreatePayload = {
  productCode: string;
  name: string;
  salesStatus: SalesStatus;
  listPrice: number;
};

export type ProductUpdatePayload = {
  name: string;
  salesStatus: SalesStatus;
  listPrice: number;
};

export type StockItem = {
  skuId: string;
  productCode: string;
  availableQuantity: number;
  reservedQuantity: number;
  version: number;
};

export type StockSetPayload = {
  productCode: string;
  availableQuantity: number;
};
