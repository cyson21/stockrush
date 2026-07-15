// mobile-app API 클라이언트 레이어: 화면에서 필요한 데이터 호출을 단일 함수로 감쌉니다.
import { apiUrl, request } from './client';
import type { PromotionQuoteRequest, PromotionQuoteResponse } from '../types/api';

export function quoteCoupon(payload: PromotionQuoteRequest): Promise<PromotionQuoteResponse> {
  return request<PromotionQuoteResponse>(apiUrl('/api/coupons/quote'), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Correlation-Id': 'mobile-coupon-quote',
    },
    body: JSON.stringify(payload),
  });
}
