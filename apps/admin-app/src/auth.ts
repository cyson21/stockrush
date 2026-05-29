// 관리자 앱 인증 유틸: OIDC 로그인/토큰/상태 저장 흐름을 통합 관리합니다.
export const AUTH_ACCESS_TOKEN_STORAGE_KEY = 'stockrush-admin-access-token';
const AUTH_ACCESS_TOKEN_EXPIRES_AT_STORAGE_KEY = 'stockrush-admin-access-token-expires-at';
const AUTH_STATE_KEY = 'stockrush-admin-oidc-state';
const AUTH_VERIFIER_KEY = 'stockrush-admin-oidc-code-verifier';

export type OidcConfig = {
  issuer: string;
  clientId: string;
};

export function getOidcConfig(): OidcConfig {
  const env = import.meta.env;

  return {
    issuer: env.VITE_AUTH_ISSUER?.trim() || 'http://localhost:28088/realms/stockrush',
    clientId: env.VITE_AUTH_CLIENT_ID?.trim() || 'stockrush-admin-web',
  };
}

function randomBytes(length: number): Uint8Array {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return bytes;
}

function toBase64Url(value: ArrayBuffer | ArrayBufferView): string {
  const bytes =
    value instanceof ArrayBuffer ? new Uint8Array(value) : new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  let result = '';

  for (const byte of bytes) {
    result += String.fromCharCode(byte);
  }

  return btoa(result).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function randomString(length = 32): string {
  const bytes = randomBytes(length);
  return toBase64Url(bytes).slice(0, length);
}

export async function generateCodeChallenge(verifier: string): Promise<string> {
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return toBase64Url(hash);
}

function persistLoginState(state: string, codeVerifier: string): void {
  localStorage.setItem(AUTH_STATE_KEY, state);
  localStorage.setItem(AUTH_VERIFIER_KEY, codeVerifier);
}

function consumeLoginState(): { state: string; verifier: string } | null {
  const state = localStorage.getItem(AUTH_STATE_KEY);
  const verifier = localStorage.getItem(AUTH_VERIFIER_KEY);

  if (!state || !verifier) {
    return null;
  }

  localStorage.removeItem(AUTH_STATE_KEY);
  localStorage.removeItem(AUTH_VERIFIER_KEY);

  return { state, verifier };
}

export async function buildLoginUrl(): Promise<string> {
  const state = randomString(24);
  const codeVerifier = randomString(80);
  const codeChallenge = await generateCodeChallenge(codeVerifier);
  const { issuer, clientId } = getOidcConfig();
  const redirectUri = `${window.location.origin}${window.location.pathname}`;

  persistLoginState(state, codeVerifier);

  const query = new URLSearchParams({
    response_type: 'code',
    client_id: clientId,
    redirect_uri: redirectUri,
    scope: 'openid',
    code_challenge_method: 'S256',
    code_challenge: codeChallenge,
    state,
  });

  return `${issuer}/protocol/openid-connect/auth?${query.toString()}`;
}

export type OidcCallback = {
  code: string;
  state: string;
};

export function parseOidcCallback(searchParams = window.location.search): OidcCallback | null {
  const params = new URLSearchParams(searchParams);
  const code = params.get('code');
  const state = params.get('state');

  if (!code || !state) {
    return null;
  }

  return { code, state };
}

export type TokenResponse = {
  access_token: string;
  expires_in?: number;
};

export async function exchangeCodeForToken(callback: OidcCallback): Promise<TokenResponse> {
  const { issuer, clientId } = getOidcConfig();
  const storedState = consumeLoginState();

  if (!storedState || storedState.state !== callback.state) {
    throw new Error('잘못된 로그인 상태입니다.');
  }

  const redirectUri = `${window.location.origin}${window.location.pathname}`;
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: clientId,
    redirect_uri: redirectUri,
    code: callback.code,
    code_verifier: storedState.verifier,
  });

  const response = await fetch(`${issuer}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: toString(body),
  });

  let payload: { [key: string]: unknown } | null = null;

  try {
    payload = (await response.json()) as { [key: string]: unknown };
  } catch {
    payload = null;
  }

  if (!response.ok) {
    const message =
      (payload?.error_description as string) || (payload?.error as string) || `로그인 토큰 발급에 실패했습니다 (${response.status})`;
    throw new Error(message);
  }

  const token = (payload as Partial<TokenResponse> | null)?.access_token;
  if (!token || typeof token !== 'string' || token.trim().length === 0) {
    throw new Error('로그인 토큰을 받지 못했습니다.');
  }

  return {
    access_token: token,
    expires_in: typeof payload?.expires_in === 'number' ? payload.expires_in : undefined,
  };
}

function toString(body: URLSearchParams): string {
  return body.toString();
}

export function getStoredAccessToken(): string | null {
  const rawExpiresAt = localStorage.getItem(AUTH_ACCESS_TOKEN_EXPIRES_AT_STORAGE_KEY);
  if (rawExpiresAt !== null) {
    const expiresAt = Number(rawExpiresAt);
    if (Number.isFinite(expiresAt) && expiresAt <= Date.now()) {
      clearAccessToken();
      return null;
    }
  }

  return localStorage.getItem(AUTH_ACCESS_TOKEN_STORAGE_KEY);
}

export function saveAccessToken(token: string, expiresInSeconds = 3600): void {
  localStorage.setItem(AUTH_ACCESS_TOKEN_STORAGE_KEY, token);
  localStorage.setItem(
    AUTH_ACCESS_TOKEN_EXPIRES_AT_STORAGE_KEY,
    String(Date.now() + Math.max(1, expiresInSeconds) * 1000),
  );
}

export function clearAccessToken(): void {
  localStorage.removeItem(AUTH_ACCESS_TOKEN_STORAGE_KEY);
  localStorage.removeItem(AUTH_ACCESS_TOKEN_EXPIRES_AT_STORAGE_KEY);
}

export function clearAuthCallbackParams(): void {
  if (!window.history?.replaceState) {
    return;
  }

  const nextUrl = `${window.location.origin}${window.location.pathname}${window.location.hash || ''}`;
  window.history.replaceState({}, '', nextUrl);
}
