import { getCurrentAccessToken } from './oidc';

function appendHeader(target: Record<string, string>, key: string, value: string | undefined) {
  if (value) {
    target[key] = value;
  }
}

export async function withAuthHeader(
  baseHeaders: Record<string, string> = {},
  accessToken?: string | null,
): Promise<Record<string, string>> {
  const headers: Record<string, string> = { ...baseHeaders };
  const token = accessToken ?? (await getCurrentAccessToken());
  appendHeader(headers, 'Authorization', token ? `Bearer ${token}` : undefined);
  return headers;
}
