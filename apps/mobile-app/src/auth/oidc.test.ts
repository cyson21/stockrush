const secureStoreValues: Record<string, string> = {};

jest.mock('expo-secure-store', () => ({
  getItemAsync: jest.fn((key: string) => Promise.resolve(secureStoreValues[key] ?? null)),
  setItemAsync: jest.fn((key: string, value: string) => {
    secureStoreValues[key] = value;
    return Promise.resolve();
  }),
  deleteItemAsync: jest.fn((key: string) => {
    delete secureStoreValues[key];
    return Promise.resolve();
  }),
}));

describe('mobile OIDC PKCE flow', () => {
  const originalEnv = process.env;
  const fetchMock = jest.fn() as jest.MockedFunction<typeof fetch>;

  beforeEach(() => {
    jest.resetModules();
    Object.keys(secureStoreValues).forEach((key) => {
      delete secureStoreValues[key];
    });
    process.env = { ...originalEnv };
    process.env.EXPO_PUBLIC_AUTH_ISSUER = 'http://10.0.2.2:28088/realms/stockrush';
    process.env.EXPO_PUBLIC_AUTH_CLIENT_ID = 'stockrush-mobile';
    process.env.EXPO_PUBLIC_AUTH_REDIRECT_MODE = 'expo-go';
    process.env.EXPO_PUBLIC_EXPO_GO_HOST = '10.0.2.2:8081';
    delete process.env.EXPO_PUBLIC_AUTH_REDIRECT_URI;
    fetchMock.mockReset();
    global.fetch = fetchMock;
  });

  afterAll(() => {
    process.env = originalEnv;
  });

  it('uses the Expo Go redirect URI in the authorization request', async () => {
    const { buildLoginRequest, clearAuthSession } = require('./oidc') as typeof import('./oidc');
    await clearAuthSession();

    const request = await buildLoginRequest();
    const authUrl = new URL(request.authUrl);

    expect(authUrl.origin).toBe('http://10.0.2.2:28088');
    expect(authUrl.pathname).toBe('/realms/stockrush/protocol/openid-connect/auth');
    expect(authUrl.searchParams.get('client_id')).toBe('stockrush-mobile');
    expect(authUrl.searchParams.get('response_type')).toBe('code');
    expect(authUrl.searchParams.get('redirect_uri')).toBe('exp://10.0.2.2:8081/--/auth/callback');
    expect(authUrl.searchParams.get('code_challenge_method')).toBe('S256');
    expect(authUrl.searchParams.get('state')).toBe(request.state);
  });

  it('accepts an Expo Go callback and exchanges the code with the same redirect URI', async () => {
    const { getCurrentAccessToken, buildLoginRequest, clearAuthSession, handleAuthRedirect } = require('./oidc') as typeof import('./oidc');
    await clearAuthSession();

    const request = await buildLoginRequest();
    fetchMock.mockResolvedValueOnce(
      new Response(
        JSON.stringify({
          access_token: 'mobile-access-token',
          refresh_token: 'mobile-refresh-token',
          token_type: 'Bearer',
          expires_in: 3600,
        }),
        {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        },
      ),
    );

    const session = await handleAuthRedirect(
      `exp://10.0.2.2:8081/--/auth/callback?code=auth-code-001&state=${request.state}`,
    );

    expect(session.accessToken).toBe('mobile-access-token');
    expect(await getCurrentAccessToken()).toBe('mobile-access-token');
    expect(fetchMock).toHaveBeenCalledWith(
      'http://10.0.2.2:28088/realms/stockrush/protocol/openid-connect/token',
      expect.objectContaining({
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      }),
    );
    const [, init] = fetchMock.mock.calls[0];
    const body = init?.body as URLSearchParams;
    expect(body.get('client_id')).toBe('stockrush-mobile');
    expect(body.get('grant_type')).toBe('authorization_code');
    expect(body.get('code')).toBe('auth-code-001');
    expect(body.get('redirect_uri')).toBe('exp://10.0.2.2:8081/--/auth/callback');
    expect(body.get('code_verifier')).toBe(request.codeVerifier);
  });
});
