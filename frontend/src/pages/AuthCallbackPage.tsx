import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export function AuthCallbackPage() {
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { completeSession } = useAuth();

  useEffect(() => {
    const error = searchParams.get('error');

    if (error) {
      navigate('/login?error=' + error, { replace: true });
      return;
    }

    // The backend set the HttpOnly session cookie on the OIDC callback response.
    // Fetch the profile using that cookie — the session credential never
    // appears in this URL or in any client-visible storage.
    fetch('/v1/auth/me', { credentials: 'same-origin' })
      .then((res) => {
        if (!res.ok) throw new Error('Failed to fetch user info');
        return res.json();
      })
      .then((user) => {
        completeSession({
          userId: user.userId,
          organizationId: user.organizationId,
          email: user.email,
          fullName: user.fullName || '',
        });
      })
      .catch(() => {
        navigate('/login?error=authentication_failed', { replace: true });
      });
  }, [searchParams, navigate, completeSession]);

  return (
    <div className="min-h-screen bg-transparent flex items-center justify-center">
      <div className="text-center">
        <div className="h-12 w-12 rounded-xl bg-gradient-to-br from-cyan-400 via-blue-500 to-violet-500 flex items-center justify-center mx-auto mb-4 animate-pulse shadow-glow-lg">
          <svg className="h-6 w-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
          </svg>
        </div>
        <p className="text-sm text-slate-400">Completing sign in...</p>
      </div>
    </div>
  );
}