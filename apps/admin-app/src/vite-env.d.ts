/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_ORDER_API_BASE_URL?: string;
  readonly VITE_INVENTORY_API_BASE_URL?: string;
  readonly VITE_PAYMENT_API_BASE_URL?: string;
  readonly VITE_CATALOG_API_BASE_URL?: string;
  readonly VITE_AUTH_ISSUER?: string;
  readonly VITE_AUTH_CLIENT_ID?: string;
}
