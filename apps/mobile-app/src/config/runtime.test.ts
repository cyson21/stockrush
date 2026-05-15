describe('mobile runtime auth redirect URI', () => {
  const originalEnv = process.env;

  beforeEach(() => {
    jest.resetModules();
    process.env = { ...originalEnv };
    delete process.env.EXPO_PUBLIC_AUTH_REDIRECT_URI;
    delete process.env.EXPO_PUBLIC_AUTH_REDIRECT_MODE;
    delete process.env.EXPO_PUBLIC_EXPO_GO_HOST;
  });

  afterAll(() => {
    process.env = originalEnv;
  });

  const loadRuntime = (): typeof import('./runtime') => require('./runtime');

  it('uses the development build custom scheme by default', () => {
    const { getAuthRedirectUri } = loadRuntime();

    expect(getAuthRedirectUri()).toBe('stockrush://auth/callback');
  });

  it('uses localhost issuer for emulator Keycloak cookie continuity', () => {
    const { getAuthIssuer } = loadRuntime();

    expect(getAuthIssuer()).toBe('http://localhost:28088/realms/stockrush');
  });

  it('uses an explicit redirect URI when provided', () => {
    process.env.EXPO_PUBLIC_AUTH_REDIRECT_URI = 'exp://192.168.0.10:8081/--/auth/callback';
    const { getAuthRedirectUri } = loadRuntime();

    expect(getAuthRedirectUri()).toBe('exp://192.168.0.10:8081/--/auth/callback');
  });

  it('builds an Expo Go redirect URI when expo-go mode is selected', () => {
    process.env.EXPO_PUBLIC_AUTH_REDIRECT_MODE = 'expo-go';
    process.env.EXPO_PUBLIC_EXPO_GO_HOST = '10.0.2.2:8081';
    const { getAuthRedirectUri } = loadRuntime();

    expect(getAuthRedirectUri()).toBe('exp://10.0.2.2:8081/--/auth/callback');
  });
});
