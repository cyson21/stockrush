// customer-app API 호출 모듈: 엔드포인트 구성과 공통 응답 처리 경계를 담당합니다.
import { apiUrl, request } from './client';
import type { Product } from '../types/api';

export function listOnSaleProducts(query?: string): Promise<Product[]> {
  const searchQuery = query?.trim();
  const params: Record<string, string> = {
    status: 'ON_SALE',
  };

  if (searchQuery) {
    params.q = searchQuery;
  }

  return request<Product[]>(apiUrl('catalog', '/api/products', params), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': 'customer-app-catalog-list',
    },
  });
}
