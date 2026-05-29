// customer-app API 호출 모듈: 엔드포인트 구성과 공통 응답 처리 경계를 담당합니다.
import { apiUrl, request } from './client';
import type { Stock } from '../types/api';

export function listStocks(productCode: string): Promise<Stock[]> {
  return request<Stock[]>(apiUrl('inventory', '/api/stocks', { productCode }), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': `customer-app-stock-${productCode}`,
    },
  });
}
