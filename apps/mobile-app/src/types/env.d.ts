declare namespace NodeJS {
  interface ProcessEnv {
    EXPO_PUBLIC_API_BASE_URL?: string;
    EXPO_PUBLIC_MEMBER_ID?: string;
    EXPO_PUBLIC_AUTH_ISSUER?: string;
    EXPO_PUBLIC_AUTH_CLIENT_ID?: string;
    EXPO_PUBLIC_AUTH_REDIRECT_URI?: string;
    EXPO_PUBLIC_AUTH_REDIRECT_MODE?: string;
    EXPO_PUBLIC_EXPO_GO_HOST?: string;
    EXPO_PUBLIC_MOBILE_SMOKE_AUTORUN?: string;
  }
}
