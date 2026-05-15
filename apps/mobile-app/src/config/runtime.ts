import { Platform } from 'react-native';

const configuredApiBaseUrl = process.env.EXPO_PUBLIC_API_BASE_URL;

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, '');
}

function defaultGatewayUrl(): string {
  if (Platform.OS === 'android') {
    return 'http://10.0.2.2:18080';
  }
  return 'http://localhost:18080';
}

export const mobileRuntimeLabel = Platform.select({
  android: 'Android emulator or device',
  ios: 'iOS simulator or device',
  default: 'Expo runtime',
});

function trimValue(value?: string): string {
  return value?.trim() || '';
}

function defaultAuthIssuer(): string {
  return 'http://localhost:28088/realms/stockrush';
}

function defaultExpoGoHost(): string {
  return Platform.OS === 'android' ? '10.0.2.2:8081' : 'localhost:8081';
}

function normalizeExpoGoHost(value?: string): string {
  const host = trimValue(value).replace(/^exp:\/\//, '').replace(/\/+$/, '');
  return host || defaultExpoGoHost();
}

export function getApiBaseUrl(): string {
  return trimTrailingSlash(configuredApiBaseUrl?.trim() || defaultGatewayUrl());
}

export function getDefaultMemberId(): string {
  return process.env.EXPO_PUBLIC_MEMBER_ID?.trim() || 'member-mobile-demo';
}

export function getAuthIssuer(): string {
  return trimTrailingSlash(trimValue(process.env.EXPO_PUBLIC_AUTH_ISSUER) || defaultAuthIssuer());
}

export function getAuthClientId(): string {
  return trimValue(process.env.EXPO_PUBLIC_AUTH_CLIENT_ID) || 'stockrush-mobile';
}

export function getAuthRedirectUri(): string {
  const configuredRedirectUri = trimValue(process.env.EXPO_PUBLIC_AUTH_REDIRECT_URI);
  if (configuredRedirectUri) {
    return configuredRedirectUri;
  }

  if (trimValue(process.env.EXPO_PUBLIC_AUTH_REDIRECT_MODE).toLowerCase() === 'expo-go') {
    return `exp://${normalizeExpoGoHost(process.env.EXPO_PUBLIC_EXPO_GO_HOST)}/--/auth/callback`;
  }

  return 'stockrush://auth/callback';
}

export function getMobileSmokeAutoRunEnabled(): boolean {
  return trimValue(process.env.EXPO_PUBLIC_MOBILE_SMOKE_AUTORUN).toLowerCase() === 'true';
}
