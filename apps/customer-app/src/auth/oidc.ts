const AUTH_SESSION_KEY = 'stockrush.customer-web.auth.session';
const OIDC_PENDING_KEY = 'stockrush.customer-web.auth.pending';

type OidcTokenResponse = {
  access_token: string;
  token_type?: string;
  expires_in?: number;
  expires_at?: number;
};

type OidcConfig = {
  issuer: string;
  clientId: string;
  scope: string;
  redirectUri: string;
};

type OidcSession = {
  accessToken: string;
  tokenType: string;
  expiresAt: number;
};

type OidcPendingAuth = {
  state: string;
  codeVerifier: string;
  redirectUri: string;
  createdAt: number;
};

const DEFAULT_SCOPE = 'openid profile email';
const ONE_HOUR_MS = 60 * 60 * 1000;

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, '');
}

function nonBlank(value: string | undefined, fallback: string): string {
  const trimmed = value?.trim();
  return trimmed && trimmed.length > 0 ? trimmed : fallback;
}

function base64UrlEncode(value: string | ArrayBuffer | ArrayBufferView): string {
  let normalized = value;
  if (typeof normalized === 'string') {
    normalized = new TextEncoder().encode(normalized);
  }

  const bytes = normalized instanceof ArrayBuffer ? new Uint8Array(normalized) : new Uint8Array(normalized.buffer.slice(normalized.byteOffset, normalized.byteOffset + normalized.byteLength));
  let binary = '';

  for (const charCode of bytes) {
    binary += String.fromCharCode(charCode);
  }

  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

async function sha256(input: string): Promise<ArrayBuffer> {
  const bytes = new TextEncoder().encode(input);
  return crypto.subtle.digest('SHA-256', bytes);
}

function randomString(length = 48): string {
  const bytes = new Uint8Array(length);
  crypto.getRandomValues(bytes);
  return base64UrlEncode(bytes);
}

function getStorage(): Storage {
  return window.sessionStorage;
}

function readJson<T>(raw: string | null): T | null {
  if (!raw) {
    return null;
  }

  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function authEndpoint(issuer: string): string {
  return `${trimTrailingSlash(issuer)}/protocol/openid-connect/auth`;
}

function tokenEndpoint(issuer: string): string {
  return `${trimTrailingSlash(issuer)}/protocol/openid-connect/token`;
}

function safeSession(session: OidcSession | null): OidcSession | null {
  if (!session || typeof session.accessToken !== 'string' || session.accessToken.trim().length === 0) {
    return null;
  }

  if (typeof session.expiresAt !== 'number' || Number.isNaN(session.expiresAt)) {
    return null;
  }

  if (session.expiresAt <= Date.now()) {
    getStorage().removeItem(AUTH_SESSION_KEY);
    return null;
  }

  return {
    ...session,
    tokenType: session.tokenType || 'Bearer',
  };
}

function mergeHeaders(headers?: HeadersInit): Record<string, string> {
  if (!headers) {
    return {};
  }

  if (headers instanceof Headers) {
    return Object.fromEntries(headers.entries());
  }

  if (Array.isArray(headers)) {
    return headers.reduce<Record<string, string>>((acc, [key, value]) => {
      acc[key] = value;
      return acc;
    }, {});
  }

  return { ...headers };
}

export function getAuthConfig(): OidcConfig {
  const env = import.meta.env;
  const issuer = trimTrailingSlash(nonBlank(env.VITE_AUTH_ISSUER, 'http://localhost:28088/realms/stockrush'));
  return {
    issuer,
    clientId: nonBlank(env.VITE_AUTH_CLIENT_ID, 'stockrush-customer-web'),
    scope: nonBlank(env.VITE_AUTH_SCOPE, DEFAULT_SCOPE),
    redirectUri: nonBlank(env.VITE_AUTH_REDIRECT_URI, `${window.location.origin}${window.location.pathname}`),
  };
}

export function getAuthSession(): OidcSession | null {
  return safeSession(readJson<OidcSession>(getStorage().getItem(AUTH_SESSION_KEY)));
}

export function getAuthToken(): string | null {
  return getAuthSession()?.accessToken ?? null;
}

export function getAuthHeader(): string | null {
  const token = getAuthToken();
  if (!token) {
    return null;
  }

  return `Bearer ${token}`;
}

export function setAuthSessionFromTokenResponse(response: OidcTokenResponse): OidcSession {
  const tokenType = response.token_type && response.token_type.trim().length > 0 ? response.token_type.trim() : 'Bearer';
  const expiresAt =
    typeof response.expires_in === 'number'
      ? Date.now() + Math.max(1, response.expires_in) * 1000
      : typeof response.expires_at === 'number'
        ? response.expires_at * 1000
        : Date.now() + ONE_HOUR_MS;

  const session: OidcSession = {
    accessToken: response.access_token,
    tokenType: tokenType === 'Bearer' ? tokenType : tokenType,
    expiresAt,
  };

  getStorage().setItem(AUTH_SESSION_KEY, JSON.stringify(session));
  return session;
}

export function clearAuthSession(): void {
  getStorage().removeItem(AUTH_SESSION_KEY);
}

export function setAuthSessionForTest(accessToken: string, expiresInSeconds = 3600): void {
  const response: OidcTokenResponse = {
    access_token: accessToken,
    token_type: 'Bearer',
    expires_in: expiresInSeconds,
  };
  setAuthSessionFromTokenResponse(response);
}

function getPendingAuth(): OidcPendingAuth | null {
  return readJson<OidcPendingAuth>(getStorage().getItem(OIDC_PENDING_KEY));
}

function clearPendingAuth(): void {
  getStorage().removeItem(OIDC_PENDING_KEY);
}

export async function buildAuthorizationUrl(config?: OidcConfig): Promise<string> {
  const resolvedConfig = config ?? getAuthConfig();
  const codeVerifier = randomString();
  const challenge = base64UrlEncode(await sha256(codeVerifier));
  const state = randomString(24);

  const url = new URL(authEndpoint(resolvedConfig.issuer));
  url.searchParams.set('response_type', 'code');
  url.searchParams.set('client_id', resolvedConfig.clientId);
  url.searchParams.set('redirect_uri', resolvedConfig.redirectUri);
  url.searchParams.set('scope', resolvedConfig.scope);
  url.searchParams.set('code_challenge', challenge);
  url.searchParams.set('code_challenge_method', 'S256');
  url.searchParams.set('state', state);

  getStorage().setItem(
    OIDC_PENDING_KEY,
    JSON.stringify({
      state,
      codeVerifier,
      redirectUri: resolvedConfig.redirectUri,
      createdAt: Date.now(),
    } satisfies OidcPendingAuth),
  );

  return url.toString();
}

export async function startLogin(): Promise<void> {
  const url = await buildAuthorizationUrl();
  window.location.assign(url);
}

function parseQueryCode(): { code?: string | null; state?: string | null; error?: string | null; errorDescription?: string | null } {
  const searchParams = new URLSearchParams(window.location.search);
  return {
    code: searchParams.get('code'),
    state: searchParams.get('state'),
    error: searchParams.get('error'),
    errorDescription: searchParams.get('error_description'),
  };
}

function clearCallbackSearchParams() {
  const url = new URL(window.location.href);
  url.search = '';
  window.history.replaceState({}, '', `${url.pathname}${url.hash}`);
}

export async function completeLoginFromCallback(): Promise<OidcSession | null> {
  const callback = parseQueryCode();

  if (callback.error) {
    clearPendingAuth();
    clearCallbackSearchParams();
    throw new Error(callback.errorDescription || 'OIDC 로그인에 실패했습니다.');
  }

  if (!callback.code || !callback.state) {
    return null;
  }

  const pendingAuth = getPendingAuth();
  if (!pendingAuth || pendingAuth.state !== callback.state) {
    clearCallbackSearchParams();
    throw new Error('OIDC state가 일치하지 않습니다.');
  }

  const config = getAuthConfig();
  const body = new URLSearchParams();
  body.set('grant_type', 'authorization_code');
  body.set('client_id', config.clientId);
  body.set('code', callback.code);
  body.set('redirect_uri', pendingAuth.redirectUri);
  body.set('code_verifier', pendingAuth.codeVerifier);

  const response = await fetch(tokenEndpoint(config.issuer), {
    method: 'POST',
    headers: mergeHeaders({
      'Content-Type': 'application/x-www-form-urlencoded',
    }),
    body,
  });

  if (!response.ok) {
    clearPendingAuth();
    clearCallbackSearchParams();
    throw new Error(`OIDC 토큰 요청 실패: ${response.status}`);
  }

  const tokenResponse = (await response.json()) as OidcTokenResponse;
  if (!tokenResponse.access_token) {
    clearPendingAuth();
    clearCallbackSearchParams();
    throw new Error('OIDC 토큰 응답에서 access token을 찾지 못했습니다.');
  }

  const session = setAuthSessionFromTokenResponse(tokenResponse);
  clearPendingAuth();
  clearCallbackSearchParams();
  return session;
}

export function getAuthorizationHeaders(existingHeaders?: HeadersInit): Record<string, string> {
  const authHeader = getAuthHeader();
  if (!authHeader) {
    return mergeHeaders(existingHeaders);
  }

  return {
    ...mergeHeaders(existingHeaders),
    Authorization: authHeader,
  };
}
