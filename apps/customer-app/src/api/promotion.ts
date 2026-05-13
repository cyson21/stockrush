import { apiUrl, request } from './client';
import type { PromotionQuoteRequest, PromotionQuoteResponse } from '../types/api';

export function quoteCoupon(payload: PromotionQuoteRequest): Promise<PromotionQuoteResponse> {
  return request<PromotionQuoteResponse>(apiUrl('promotion', '/api/coupons/quote'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Correlation-Id': 'customer-app-coupon-quote',
    },
    body: JSON.stringify(payload),
  });
}
