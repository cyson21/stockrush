// 타입 정의 모듈: API/도메인 데이터 형태를 명시적으로 문서화합니다.
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

export type Product = {
  productCode: string;
  name: string;
  status: string;
  listPrice: number;
};

export type Stock = {
  skuId: string;
  productCode: string;
  availableQuantity: number;
  reservedQuantity: number;
  version: number;
};

export type CreateOrderItemRequest = {
  productCode: string;
  skuId: string;
  quantity: number;
  unitPrice: number;
};

export type CreateOrderRequest = {
  memberId: string;
  paymentMethod: string;
  items: CreateOrderItemRequest[];
  couponCode?: string;
};

export type CreateOrderResponse = {
  orderId: string;
  status: string;
  sagaStatus: string;
  paymentMethod: string;
  couponCode?: string | null;
  totalAmount: number;
  discountAmount: number;
  payableAmount: number;
};

export type OrderDetailItem = CreateOrderItemRequest & {
  lineAmount: number;
};

export type OrderDetail = {
  orderId: string;
  memberId: string;
  status: string;
  sagaStatus: string;
  paymentMethod: string;
  couponCode?: string | null;
  totalAmount: number;
  discountAmount: number;
  payableAmount: number;
  items: OrderDetailItem[];
};

export type PromotionQuoteRequest = {
  couponCode: string;
  orderAmount: number;
};

export type PromotionQuoteResponse = {
  couponCode: string;
  applied: boolean;
  discountAmount: number;
  payAmount: number;
  reason: string;
};
