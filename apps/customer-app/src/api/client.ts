import type { ApiError, ApiResponse } from '../types/api';

type ServiceName = 'catalog' | 'inventory' | 'orders';

const defaultServicePrefixes: Record<ServiceName, string> = {
  catalog: '/catalog',
  inventory: '/inventory',
  orders: '/orders',
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
    catalog: env.VITE_CATALOG_API_BASE_URL,
    inventory: env.VITE_INVENTORY_API_BASE_URL,
    orders: env.VITE_ORDER_API_BASE_URL,
  };

  if (serviceBaseUrls[serviceName]) {
    return trimTrailingSlash(serviceBaseUrls[serviceName]);
  }

  return `${baseUrl}${defaultServicePrefixes[serviceName]}`;
}

export function apiUrl(serviceName: ServiceName, path: string, params?: Record<string, string>): string {
  const url = new URL(`${serviceBaseUrl(serviceName)}${withLeadingSlash(path)}`, window.location.origin);

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
    super(apiError?.message ?? `Request failed with status ${status}`);
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

export function makeIdempotencyKey(): string {
  return `customer-app-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}
