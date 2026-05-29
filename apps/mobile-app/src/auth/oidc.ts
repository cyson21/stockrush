// 인증·세션·토큰·리다이렉트 흐름을 한 곳에서 관리하는 경계입니다.
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
  error?: string;
  error_description?: string;
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
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  const arrayLike = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let output = '';

  for (let index = 0; index < arrayLike.length; index += 3) {
    const first = arrayLike[index];
    const second = arrayLike[index + 1];
    const third = arrayLike[index + 2];
    const combined = (first << 16) | ((second ?? 0) << 8) | (third ?? 0);

    output += alphabet[(combined >> 18) & 63];
    output += alphabet[(combined >> 12) & 63];
    output += index + 1 < arrayLike.length ? alphabet[(combined >> 6) & 63] : '=';
    output += index + 2 < arrayLike.length ? alphabet[combined & 63] : '=';
  }

  return output.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function utf8Bytes(value: string): Uint8Array {
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(value);
  }

  const bytes: number[] = [];
  for (let index = 0; index < value.length; index += 1) {
    const codePoint = value.charCodeAt(index);
    if (codePoint < 0x80) {
      bytes.push(codePoint);
    } else if (codePoint < 0x800) {
      bytes.push(0xc0 | (codePoint >> 6), 0x80 | (codePoint & 0x3f));
    } else {
      bytes.push(0xe0 | (codePoint >> 12), 0x80 | ((codePoint >> 6) & 0x3f), 0x80 | (codePoint & 0x3f));
    }
  }

  return new Uint8Array(bytes);
}

function rightRotate(value: number, shift: number): number {
  return (value >>> shift) | (value << (32 - shift));
}

function sha256Bytes(input: Uint8Array): Uint8Array {
  const constants = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ];
  const hash = [
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ];
  const bitLength = input.length * 8;
  const paddedLength = (((input.length + 9 + 63) >> 6) << 6);
  const padded = new Uint8Array(paddedLength);
  padded.set(input);
  padded[input.length] = 0x80;

  const view = new DataView(padded.buffer);
  view.setUint32(paddedLength - 4, bitLength, false);

  for (let offset = 0; offset < paddedLength; offset += 64) {
    const words = new Array<number>(64);
    for (let index = 0; index < 16; index += 1) {
      words[index] = view.getUint32(offset + index * 4, false);
    }

    for (let index = 16; index < 64; index += 1) {
      const s0 = rightRotate(words[index - 15], 7) ^ rightRotate(words[index - 15], 18) ^ (words[index - 15] >>> 3);
      const s1 = rightRotate(words[index - 2], 17) ^ rightRotate(words[index - 2], 19) ^ (words[index - 2] >>> 10);
      words[index] = (words[index - 16] + s0 + words[index - 7] + s1) >>> 0;
    }

    let [a, b, c, d, e, f, g, h] = hash;
    for (let index = 0; index < 64; index += 1) {
      const s1 = rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25);
      const ch = (e & f) ^ (~e & g);
      const temp1 = (h + s1 + ch + constants[index] + words[index]) >>> 0;
      const s0 = rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const temp2 = (s0 + maj) >>> 0;

      h = g;
      g = f;
      f = e;
      e = (d + temp1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (temp1 + temp2) >>> 0;
    }

    hash[0] = (hash[0] + a) >>> 0;
    hash[1] = (hash[1] + b) >>> 0;
    hash[2] = (hash[2] + c) >>> 0;
    hash[3] = (hash[3] + d) >>> 0;
    hash[4] = (hash[4] + e) >>> 0;
    hash[5] = (hash[5] + f) >>> 0;
    hash[6] = (hash[6] + g) >>> 0;
    hash[7] = (hash[7] + h) >>> 0;
  }

  const output = new Uint8Array(32);
  const outputView = new DataView(output.buffer);
  hash.forEach((value, index) => outputView.setUint32(index * 4, value, false));
  return output;
}

async function hashForPkce(verifier: string): Promise<string> {
  const hasher = globalThis.crypto?.subtle;
  if (!hasher) {
    return base64UrlEncode(sha256Bytes(utf8Bytes(verifier)));
  }

  const encoded = utf8Bytes(verifier);
  const digestInput = encoded.buffer.slice(encoded.byteOffset, encoded.byteOffset + encoded.byteLength) as ArrayBuffer;
  const digest = await hasher.digest('SHA-256', digestInput);
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

  const tokenRequestBody = new URLSearchParams({
    client_id: getAuthClientId(),
    grant_type: 'authorization_code',
    code,
    redirect_uri: pending.redirectUri,
    code_verifier: pending.codeVerifier,
  }).toString();

  const tokenResponse = await fetch(buildTokenUrl(), {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
    },
    body: tokenRequestBody,
  });

  let tokenPayload: AuthTokenResponse = {};
  try {
    tokenPayload = (await tokenResponse.json()) as AuthTokenResponse;
  } catch (error) {
    throw new Error('AUTH_TOKEN_PARSE_ERROR');
  }

  if (!tokenResponse.ok || !tokenPayload.access_token) {
    const reason = tokenPayload.error_description ?? tokenPayload.error;
    throw new Error(reason ? `AUTH_TOKEN_EXCHANGE_FAILED:${reason}` : 'AUTH_TOKEN_EXCHANGE_FAILED');
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

export function isAuthRedirectUrl(url: string): boolean {
  try {
    const parsed = new URL(url);
    return parsed.searchParams.has('state') && (parsed.searchParams.has('code') || parsed.searchParams.has('error'));
  } catch {
    return false;
  }
}

export async function clearAuthSession(): Promise<void> {
  await saveAuthSession(null);
  await clearPendingAuthRequest();
}

export async function getCurrentAccessToken(): Promise<string | null> {
  return getAccessToken();
}
