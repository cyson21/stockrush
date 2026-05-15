import { createContext, type PropsWithChildren, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { Linking } from 'react-native';
import { buildLoginRequest, clearAuthSession, getCurrentAccessToken, handleAuthRedirect, isAuthRedirectUrl } from './oidc';
import { saveAuthSession } from './session';
import type { StoredAuthSession } from './session';

type AuthContextValue = {
  isAuthenticated: boolean;
  accessToken: string | null;
  isLoading: boolean;
  error: string | null;
  login: () => Promise<void>;
  logout: () => Promise<void>;
  handleOpenUrl: (url: string) => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({
  children,
  initialAccessToken,
}: PropsWithChildren<{ initialAccessToken?: string | null }>) {
  const [accessToken, setAccessToken] = useState<string | null>(initialAccessToken ?? null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const handledRedirectUrlsRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    let isActive = true;

    const restore = async () => {
      if (initialAccessToken === null) {
        setAccessToken(null);
        await saveAuthSession(null);
        return;
      }

      if (initialAccessToken !== undefined) {
        const initialSession: StoredAuthSession = {
          accessToken: initialAccessToken,
          tokenType: 'Bearer',
        };
        setAccessToken(initialAccessToken);
        await saveAuthSession(initialSession);
        return;
      }

      const token = await getCurrentAccessToken();
      if (isActive && token) {
        setAccessToken(token);
      }
    };

    void restore();

    return () => {
      isActive = false;
    };
  }, [initialAccessToken]);

  const login = useCallback(async () => {
    setError(null);
    setIsLoading(true);
    try {
      const request = await buildLoginRequest();
      await Linking.openURL(request.authUrl);
    } catch (error) {
      setError(error instanceof Error ? error.message : '로그인 요청 실패');
    } finally {
      setIsLoading(false);
    }
  }, []);

  const handleOpenUrl = useCallback(async (url: string) => {
    if (!isAuthRedirectUrl(url) || handledRedirectUrlsRef.current.has(url)) {
      return;
    }

    handledRedirectUrlsRef.current.add(url);
    setError(null);
    setIsLoading(true);
    try {
      const session = await handleAuthRedirect(url);
      setAccessToken(session.accessToken);
      await saveAuthSession(session);
    } catch (error) {
      setError(error instanceof Error ? error.message : '로그인 콜백 처리 실패');
    } finally {
      setIsLoading(false);
    }
  }, []);

  const logout = useCallback(async () => {
    setIsLoading(true);
    await clearAuthSession();
    setAccessToken(null);
    setError(null);
    setIsLoading(false);
  }, []);

  useEffect(() => {
    let isActive = true;

    const subscription = Linking.addEventListener('url', ({ url }) => {
      void handleOpenUrl(url);
    });

    void (async () => {
      const initialUrl = await Linking.getInitialURL();
      if (initialUrl && isActive) {
        await handleOpenUrl(initialUrl);
      }
    })();

    return () => {
      isActive = false;
      subscription.remove();
    };
  }, [handleOpenUrl]);

  const value = useMemo(
    () => ({
      isAuthenticated: Boolean(accessToken),
      accessToken,
      isLoading,
      error,
      login,
      logout,
      handleOpenUrl,
    }),
    [accessToken, error, isLoading, handleOpenUrl, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider');
  }
  return context;
}
