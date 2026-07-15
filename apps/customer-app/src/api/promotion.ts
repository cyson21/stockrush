// customer-app API 호출 모듈: 엔드포인트 구성과 공통 응답 처리 경계를 담당합니다.
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
