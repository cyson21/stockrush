import { getAuthClientId, getAuthIssuer, getAuthRedirectUri } from '../config/runtime';
import {
  clearPendingAuthRequest,
  getAccessToken,
  getPendingAuthRequest,
  saveAuthSession,
  savePendingAuthRequest,
  StoredAuthSession,
} from './session';

const AUTH_CODE_VERIFIER_LENGTH = 96;
const AUTH_STATE_LENGTH = 24;

type AuthTokenResponse = {
  access_token?: string;
  refresh_token?: string | null;
  token_type?: string;
  expires_in?: number;
};

type PendingLoginState = {
  state: string;
  codeVerifier: string;
  redirectUri: string;
};

const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';

function randomString(length: number): string {
  let output = '';

  for (let index = 0; index < length; index += 1) {
    output += ALPHABET[Math.floor(Math.random() * ALPHABET.length)];
  }

  return output;
}

function normalizeIssuer(issuer: string): string {
  const normalized = issuer.trim().replace(/\/+$/, '');
  if (/\/protocol\/openid-connect\/?$/.test(normalized)) {
    return normalized;
  }

  return `${normalized}`;
}

function base64UrlEncode(bytes: Uint8Array | number[]): string {
  let binary = '';
  const arrayLike = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);

  arrayLike.forEach((byte) => {
    binary += String.fromCharCode(byte);
  });

  const base64 = typeof btoa === 'function' ? btoa(binary) : globalThis.Buffer?.from(binary, 'binary').toString('base64');
  return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

async function hashForPkce(verifier: string): Promise<string> {
  const hasher = globalThis.crypto?.subtle;
  if (!hasher || typeof TextEncoder === 'undefined') {
    const bufferFactory = (globalThis as { Buffer?: { from: (value: string, encoding: string) => { toString: (encoding: string) => string } } }).Buffer;
    if (bufferFactory) {
      return base64UrlEncode(Array.from(bufferFactory.from(verifier, 'utf8').toString('binary').split('').map((char) => char.charCodeAt(0))));
    }

    return verifier;
  }

  const encoded = new TextEncoder().encode(verifier);
  const digest = await hasher.digest('SHA-256', encoded);
  return base64UrlEncode(new Uint8Array(digest));
}

function buildBaseAuthUrl(): string {
  const issuer = normalizeIssuer(getAuthIssuer());
  return `${issuer}/protocol/openid-connect/auth`;
}

function buildTokenUrl(): string {
  const issuer = normalizeIssuer(getAuthIssuer());
  return `${issuer}/protocol/openid-connect/token`;
}

export async function buildLoginRequest(): Promise<{ authUrl: string; state: string; codeVerifier: string; codeChallenge: string }> {
  const state = randomString(AUTH_STATE_LENGTH);
  const codeVerifier = randomString(AUTH_CODE_VERIFIER_LENGTH);
  const codeChallenge = await hashForPkce(codeVerifier);
  const redirectUri = getAuthRedirectUri();
  const query = new URLSearchParams({
    client_id: getAuthClientId(),
    response_type: 'code',
    redirect_uri: redirectUri,
    scope: 'openid',
    state,
    code_challenge: codeChallenge,
    code_challenge_method: 'S256',
  });

  await savePendingAuthRequest({
    state,
    codeVerifier,
    redirectUri,
  });

  const authUrl = `${buildBaseAuthUrl()}?${query.toString()}`;
  return { authUrl, state, codeVerifier, codeChallenge };
}

export async function handleAuthRedirect(url: string): Promise<StoredAuthSession> {
  const parsed = new URL(url);
  const code = parsed.searchParams.get('code');
  const state = parsed.searchParams.get('state');
  const pending: PendingLoginState | null = await getPendingAuthRequest();

  if (!code || !state) {
    throw new Error('AUTH_MISSING_CODE');
  }

  if (!pending || pending.state !== state) {
    throw new Error('AUTH_STATE_MISMATCH');
  }

  const tokenResponse = await fetch(buildTokenUrl(), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: new URLSearchParams({
      client_id: getAuthClientId(),
      grant_type: 'authorization_code',
      code,
      redirect_uri: pending.redirectUri,
      code_verifier: pending.codeVerifier,
    }),
  });

  let tokenPayload: AuthTokenResponse = {};
  try {
    tokenPayload = (await tokenResponse.json()) as AuthTokenResponse;
  } catch (error) {
    throw new Error('AUTH_TOKEN_PARSE_ERROR');
  }

  if (!tokenResponse.ok || !tokenPayload.access_token) {
    throw new Error('AUTH_TOKEN_EXCHANGE_FAILED');
  }

  const session: StoredAuthSession = {
    accessToken: tokenPayload.access_token,
    refreshToken: tokenPayload.refresh_token ?? null,
    tokenType: tokenPayload.token_type ?? 'Bearer',
    expiresAt: tokenPayload.expires_in ? Date.now() + tokenPayload.expires_in * 1000 : null,
  };

  await saveAuthSession(session);
  await clearPendingAuthRequest();
  return session;
}

export async function clearAuthSession(): Promise<void> {
  await saveAuthSession(null);
  await clearPendingAuthRequest();
}

export async function getCurrentAccessToken(): Promise<string | null> {
  return getAccessToken();
}
