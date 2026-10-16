import { createContext, useContext, useState, useCallback, useEffect, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';

interface AuthState {
  userId: string;
  organizationId: string;
  email: string;
  fullName: string;
}

interface AuthContextType {
  auth: AuthState | null;
  login: (email: string, password: string) => Promise<void>;
  completeSession: (auth: AuthState) => void;
  logout: () => void;
  isAuthenticated: boolean;
  /**
   * True once the initial session state is settled: immediately when no cached
   * profile exists, or after the background server revalidation completes when
   * one does. Gates the first-entry loader so a revoked session never flashes
   * protected content; it does not delay interaction once resolved.
   */
  authReady: boolean;
}

const AuthContext = createContext<AuthContextType | null>(null);

// Stores ONLY non-credential identity. The session credential lives solely in
// an HttpOnly cookie and is never persisted to, or read from, JS storage.
const PROFILE_STORAGE_KEY = 'netherite_profile';

function loadProfile(): AuthState | null {
  try {
    const raw = localStorage.getItem(PROFILE_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as AuthState;
    if (!parsed.userId) return null;
    return parsed;
  } catch {
    return null;
  }
}

function saveProfile(auth: AuthState) {
  localStorage.setItem(PROFILE_STORAGE_KEY, JSON.stringify(auth));
}

function clearProfile() {
  localStorage.removeItem(PROFILE_STORAGE_KEY);
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [auth, setAuth] = useState<AuthState | null>(loadProfile);
  const [authReady, setAuthReady] = useState<boolean>(() => loadProfile() === null);
  const navigate = useNavigate();

  // Validate the cookie-backed session on startup: if we have a cached profile
  // but the server no longer accepts the session (expired/revoked), sign out.
  // authReady resolves when this check settles (or immediately with no profile).
  useEffect(() => {
    let cancelled = false;
    if (loadProfile()) {
      fetch('/v1/auth/me', { credentials: 'same-origin' })
        .then((res) => {
          if (!res.ok) throw new Error('Session not accepted');
          return res.json();
        })
        .then((user) => {
          if (cancelled) return;
          const fresh: AuthState = {
            userId: user.userId,
            organizationId: user.organizationId,
            email: user.email,
            fullName: user.fullName || '',
          };
          saveProfile(fresh);
          setAuth(fresh);
          setAuthReady(true);
        })
        .catch(() => {
          if (cancelled) return;
          clearProfile();
          setAuth(null);
          setAuthReady(true);
        });
    }
    return () => {
      cancelled = true;
    };
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    const res = await fetch('/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });

    if (!res.ok) {
      const body = await res.json().catch(() => ({ message: 'Login failed' }));
      throw new Error(body.message || 'Login failed');
    }

    // The login response sets the HttpOnly session cookie. Fetch the profile
    // without ever placing the credential in JS-accessible storage.
    const profileRes = await fetch('/v1/auth/me', { credentials: 'same-origin' });
    if (!profileRes.ok) {
      throw new Error('Failed to load profile');
    }
    const user = await profileRes.json();
    const newAuth: AuthState = {
      userId: user.userId,
      organizationId: user.organizationId,
      email: user.email,
      fullName: user.fullName || '',
    };
    saveProfile(newAuth);
    setAuth(newAuth);
    navigate('/');
  }, [navigate]);

  const logout = useCallback(async () => {
    try {
      await fetch('/v1/auth/logout', {
        method: 'POST',
        credentials: 'same-origin',
      });
    } catch {
      // ignore
    }
    clearProfile();
    setAuth(null);
    navigate('/login');
  }, [navigate]);

  const completeSession = useCallback((newAuth: AuthState) => {
    saveProfile(newAuth);
    setAuth(newAuth);
    navigate('/', { replace: true });
  }, [navigate]);

  return (
    <AuthContext.Provider value={{ auth, login, completeSession, logout, isAuthenticated: auth !== null, authReady }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextType {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}