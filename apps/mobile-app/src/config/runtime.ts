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

export function getApiBaseUrl(): string {
  return trimTrailingSlash(configuredApiBaseUrl?.trim() || defaultGatewayUrl());
}

export function getDefaultMemberId(): string {
  return process.env.EXPO_PUBLIC_MEMBER_ID?.trim() || 'member-mobile-demo';
}
