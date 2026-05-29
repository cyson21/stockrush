// mobile-app API 클라이언트 레이어: 화면에서 필요한 데이터 호출을 단일 함수로 감쌉니다.
import { apiUrl, request } from './client';
import type { Stock } from '../types/api';

export function listStocks(productCode: string): Promise<Stock[]> {
  return request<Stock[]>(apiUrl('/api/stocks', { productCode }), {
    method: 'GET',
    headers: {
      'X-Correlation-Id': `mobile-stock-${productCode}`,
    },
  });
}
