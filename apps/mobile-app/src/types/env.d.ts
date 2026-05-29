// 타입 정의 모듈: API/도메인 데이터 형태를 명시적으로 문서화합니다.
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
