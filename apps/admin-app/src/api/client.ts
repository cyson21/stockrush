import type { ApiError, ApiResponse } from '../types/admin';

type ServiceName = 'order' | 'inventory' | 'payment' | 'catalog';

const defaultServicePrefixes: Record<ServiceName, string> = {
  order: '/orders',
  inventory: '/inventory',
  payment: '/payment',
  catalog: '/catalog',
};

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, '');
}

function withLeadingSlash(value: string): string {
  return value.startsWith('/') ? value : `/${value}`;
}

function serviceBaseUrl(serviceName: ServiceName): string {
  const env = import.meta.env;
  const baseUrl = trimTrailingSlash(env.VITE_API_BASE_URL ?? '');
  const serviceBaseUrls: Record<ServiceName, string | undefined> = {
    order: env.VITE_ORDER_API_BASE_URL,
    inventory: env.VITE_INVENTORY_API_BASE_URL,
    payment: env.VITE_PAYMENT_API_BASE_URL,
    catalog: env.VITE_CATALOG_API_BASE_URL,
  };

  if (serviceBaseUrls[serviceName]) {
    return trimTrailingSlash(serviceBaseUrls[serviceName]);
  }

  return `${baseUrl}${defaultServicePrefixes[serviceName]}`;
}

export function apiUrl(service: ServiceName, path: string, params?: Record<string, string>): string {
  const baseUrl = serviceBaseUrl(service);
  const url = new URL(`${baseUrl}${withLeadingSlash(path)}`, window.location.origin);

  Object.entries(params ?? {}).forEach(([key, value]) => {
    url.searchParams.set(key, value);
  });

  if (url.origin === window.location.origin) {
    return `${url.pathname}${url.search}`;
  }

  return url.toString();
}

export class ApiClientError extends Error {
  readonly status: number;
  readonly apiError: ApiError | null;

  constructor(status: number, apiError: ApiError | null) {
    super(apiError?.message ?? `요청이 실패했습니다 (${status})`);
    this.status = status;
    this.apiError = apiError;
  }
}

export async function request<T>(input: RequestInfo | URL, init?: RequestInit): Promise<T> {
  const response = await fetch(input, init);
  let body: ApiResponse<T> | null = null;

  try {
    body = (await response.json()) as ApiResponse<T>;
  } catch {
    body = null;
  }

  if (!response.ok || !body?.success || body.data === null) {
    throw new ApiClientError(response.status, body?.error ?? null);
  }

  return body.data;
}
