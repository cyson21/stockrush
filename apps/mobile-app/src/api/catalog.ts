// mobile-app API 클라이언트 레이어: 화면에서 필요한 데이터 호출을 단일 함수로 감쌉니다.
import { apiUrl, request } from './client';
import type { Product } from '../types/api';

export function listOnSaleProducts(): Promise<Product[]> {
  return request<Product[]>(apiUrl('/api/products', { status: 'ON_SALE' }), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': 'mobile-catalog-list',
    },
  });
}
