/// <reference types="vite/client" />
// 타입 바인딩과 환경 변수 인터페이스를 정리해 컴파일 안정성을 높입니다.

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_ORDER_API_BASE_URL?: string;
  readonly VITE_INVENTORY_API_BASE_URL?: string;
  readonly VITE_PAYMENT_API_BASE_URL?: string;
  readonly VITE_CATALOG_API_BASE_URL?: string;
  readonly VITE_AUTH_ISSUER?: string;
  readonly VITE_AUTH_CLIENT_ID?: string;
}
