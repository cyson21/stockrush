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

describe('mobile auth session storage', () => {
  const originalLocalStorage = globalThis.localStorage;
  const originalSessionStorage = globalThis.sessionStorage;

  beforeEach(() => {
    jest.resetModules();
    Object.keys(secureStoreValues).forEach((key) => {
      delete secureStoreValues[key];
    });
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: undefined,
    });
    Object.defineProperty(globalThis, 'sessionStorage', {
      configurable: true,
      value: undefined,
    });
  });

  afterAll(() => {
    Object.defineProperty(globalThis, 'localStorage', {
      configurable: true,
      value: originalLocalStorage,
    });
    Object.defineProperty(globalThis, 'sessionStorage', {
      configurable: true,
      value: originalSessionStorage,
    });
  });

  it('keeps pending auth state in persistent native storage across module reloads', async () => {
    const firstSessionModule = require('./session') as typeof import('./session');

    await firstSessionModule.savePendingAuthRequest({
      state: 'state-expo-go',
      codeVerifier: 'verifier-expo-go',
      redirectUri: 'exp://127.0.0.1:8081/--/auth/callback',
    });

    jest.resetModules();
    const secondSessionModule = require('./session') as typeof import('./session');

    await expect(secondSessionModule.getPendingAuthRequest()).resolves.toEqual({
      state: 'state-expo-go',
      codeVerifier: 'verifier-expo-go',
      redirectUri: 'exp://127.0.0.1:8081/--/auth/callback',
    });
  });
});
