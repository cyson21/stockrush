import * as SecureStore from 'expo-secure-store';

export type StoredAuthSession = {
  accessToken: string;
  refreshToken?: string | null;
  tokenType?: string | null;
  expiresAt?: number | null;
};

type PendingAuthSession = {
  state: string;
  codeVerifier: string;
  redirectUri: string;
};

type StorageLike = {
  getItem: (key: string) => string | null | Promise<string | null>;
  setItem: (key: string, value: string) => void | Promise<void>;
  removeItem: (key: string) => void | Promise<void>;
};

const TOKEN_STORAGE_KEY = 'stockrush-mobile-auth-session';
const PENDING_AUTH_KEY = 'stockrush-mobile-auth-pending';
const memoryStorage: Record<string, string> = {};

function getStorage(): StorageLike {
  const candidateStorage = (value: unknown): value is StorageLike => {
    if (!value || typeof value !== 'object') {
      return false;
    }

    const storageLike = value as {
      getItem?: unknown;
      setItem?: unknown;
      removeItem?: unknown;
    };

    return (
      typeof storageLike.getItem === 'function' &&
      typeof storageLike.setItem === 'function' &&
      typeof storageLike.removeItem === 'function'
    );
  };

  const localStorageLike = (globalThis as { localStorage?: unknown }).localStorage;
  if (candidateStorage(localStorageLike)) {
    return localStorageLike;
  }

  const sessionStorageLike = (globalThis as { sessionStorage?: unknown }).sessionStorage;
  if (candidateStorage(sessionStorageLike)) {
    return sessionStorageLike;
  }

  if (
    typeof SecureStore.getItemAsync === 'function' &&
    typeof SecureStore.setItemAsync === 'function' &&
    typeof SecureStore.deleteItemAsync === 'function'
  ) {
    return {
      getItem: (key) => SecureStore.getItemAsync(key),
      setItem: (key, value) => SecureStore.setItemAsync(key, value),
      removeItem: (key) => SecureStore.deleteItemAsync(key),
    };
  }

  return {
    getItem: (key) => memoryStorage[key] ?? null,
    setItem: (key, value) => {
      memoryStorage[key] = value;
    },
    removeItem: (key) => {
      delete memoryStorage[key];
    },
  };
}

function safeJsonParse<T>(value: string | null): T | null {
  if (!value) {
    return null;
  }

  try {
    return JSON.parse(value) as T;
  } catch {
    return null;
  }
}

export async function saveAuthSession(session: StoredAuthSession | null): Promise<void> {
  const storage = getStorage();

  if (session === null) {
    await storage.removeItem(TOKEN_STORAGE_KEY);
    return;
  }

  await storage.setItem(TOKEN_STORAGE_KEY, JSON.stringify(session));
}

export async function getAuthSession(): Promise<StoredAuthSession | null> {
  const storage = getStorage();
  const session = safeJsonParse<StoredAuthSession>(await storage.getItem(TOKEN_STORAGE_KEY));
  if (typeof session?.expiresAt === 'number' && session.expiresAt <= Date.now()) {
    await storage.removeItem(TOKEN_STORAGE_KEY);
    return null;
  }
  return session;
}

export async function getAccessToken(): Promise<string | null> {
  return (await getAuthSession())?.accessToken ?? null;
}

export async function savePendingAuthRequest(pending: PendingAuthSession): Promise<void> {
  const storage = getStorage();
  await storage.setItem(PENDING_AUTH_KEY, JSON.stringify(pending));
}

export async function getPendingAuthRequest(): Promise<PendingAuthSession | null> {
  const storage = getStorage();
  return safeJsonParse(await storage.getItem(PENDING_AUTH_KEY));
}

export async function clearPendingAuthRequest(): Promise<void> {
  const storage = getStorage();
  await storage.removeItem(PENDING_AUTH_KEY);
}
