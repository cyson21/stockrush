// mobile-app API 클라이언트 레이어: 화면에서 필요한 데이터 호출을 단일 함수로 감쌉니다.
import { getApiBaseUrl } from '../config/runtime';
import type { ApiError, ApiResponse } from '../types/api';

function withLeadingSlash(path: string): string {
  return path.startsWith('/') ? path : `/${path}`;
}

export function apiUrl(path: string, params?: Record<string, string | number | undefined>): string {
  const url = new URL(`${getApiBaseUrl()}${withLeadingSlash(path)}`);

  Object.entries(params ?? {}).forEach(([key, value]) => {
    if (value !== undefined) {
      url.searchParams.set(key, String(value));
    }
  });

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

export async function request<T>(input: string, init?: RequestInit): Promise<T> {
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

export function makeIdempotencyKey(scope: string): string {
  return `mobile-${scope}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}
