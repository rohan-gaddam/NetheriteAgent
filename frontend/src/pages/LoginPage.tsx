import { useState, type FormEvent } from 'react';
import { Shield, Loader2, ArrowRight } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { Reveal } from '../components/ui/Reveal';

export function LoginPage() {
  const { login } = useAuth();
  const [mode, setMode] = useState<'sso' | 'owner' | 'redirecting'>('sso');
  const [workEmail, setWorkEmail] = useState('');
  const [ownerEmail, setOwnerEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [ssoLoading, setSsoLoading] = useState(false);

  const urlError = new URLSearchParams(window.location.search).get('error');
  const displayError = error || (urlError ? getErrorMessage(urlError) : null);

  const handleSsoSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setSsoLoading(true);
    try {
      const res = await fetch('/v1/auth/oidc/discover', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: workEmail }),
      });
      const data = await res.json();
      if (data.available) {
        setMode('redirecting');
        // Redirect to start — browser navigates to the organization's IdP
        window.location.href = '/v1/auth/oidc/start?email=' + encodeURIComponent(workEmail.trim().toLowerCase());
        return;
      }
      setSsoLoading(false);
      setError(data.message || getErrorMessage(data.errorCode || 'unknown_organization'));
    } catch {
      setSsoLoading(false);
      setError('Unable to reach the server. Please try again.');
    }
  };

  const handleOwnerSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await login(ownerEmail, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-transparent flex items-center justify-center px-4">
      <Reveal className="w-full max-w-sm">
      <div className="w-full max-w-sm">
        <div className="flex flex-col items-center mb-8">
          <div className="h-12 w-12 rounded-xl bg-gradient-to-br from-cyan-400 via-blue-500 to-violet-500 flex items-center justify-center shadow-glow-lg mb-4">
            <Shield className="h-6 w-6 text-white" />
          </div>
          <h1 className="font-display text-2xl font-bold text-glow tracking-tight">NetheriteAgent</h1>
          <p className="text-sm text-slate-400 mt-1">Sign in to your organization</p>
        </div>

        <div className="panel-featured rounded-xl p-6 space-y-4">
          {displayError && (
            <div className="bg-red-500/10 border border-red-500/20 rounded-lg px-4 py-3">
              <p className="text-sm text-red-400">{displayError}</p>
            </div>
          )}

          {mode === 'redirecting' ? (
            <div className="flex flex-col items-center py-6 space-y-3">
              <Loader2 className="h-8 w-8 text-cyan-300 animate-spin" />
              <p className="text-sm text-slate-300 text-center">
                Redirecting to your organization's<br />secure sign-in...
              </p>
            </div>
          ) : mode === 'sso' ? (
            <form onSubmit={handleSsoSubmit} className="space-y-4">
              <div>
                <label htmlFor="work-email" className="block text-sm font-medium text-slate-300 mb-1.5">
                  Work email
                </label>
                <input
                  id="work-email"
                  type="email"
                  required
                  value={workEmail}
                  onChange={(e) => setWorkEmail(e.target.value)}
                  className="w-full px-3 py-2 bg-slate-800 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400 focus:border-cyan-400/70"
                  placeholder="employee@company.com"
                  autoFocus
                />
              </div>

              <button
                type="submit"
                disabled={ssoLoading || !workEmail.trim()}
                className="w-full py-2.5 btn-luminous disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors flex items-center justify-center gap-2"
              >
                {ssoLoading ? (
                  <>
                    <Loader2 className="h-4 w-4 animate-spin" />
                    Checking...
                  </>
                ) : (
                  <>
                    Continue with SSO
                    <ArrowRight className="h-4 w-4" />
                  </>
                )}
              </button>

              <div className="pt-2 text-center">
                <button
                  type="button"
                  onClick={() => {
                    setMode('owner');
                    setError(null);
                  }}
                  className="text-xs text-slate-500 hover:text-slate-400 transition-colors"
                >
                  Owner Login
                </button>
              </div>
            </form>
          ) : (
            <>
              <p className="text-xs text-slate-400 font-medium">Owner Access</p>

              <form onSubmit={handleOwnerSubmit} className="space-y-4">
                <div>
                  <label htmlFor="owner-email" className="block text-sm font-medium text-slate-300 mb-1.5">
                    Email
                  </label>
                  <input
                    id="owner-email"
                    type="email"
                    required
                    value={ownerEmail}
                    onChange={(e) => setOwnerEmail(e.target.value)}
                    className="w-full px-3 py-2 bg-slate-800 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400 focus:border-cyan-400/70"
                    placeholder="Enter your email"
                    autoFocus
                  />
                </div>

                <div>
                  <label htmlFor="owner-password" className="block text-sm font-medium text-slate-300 mb-1.5">
                    Password
                  </label>
                  <input
                    id="owner-password"
                    type="password"
                    required
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="w-full px-3 py-2 bg-slate-800 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400 focus:border-cyan-400/70"
                    placeholder="Enter your password"
                  />
                </div>

                <button
                  type="submit"
                  disabled={loading}
                  className="w-full py-2.5 btn-luminous disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors"
                >
                  {loading ? 'Signing in...' : 'Sign in'}
                </button>
              </form>

              <div className="pt-2 text-center">
                <button
                  type="button"
                  onClick={() => {
                    setMode('sso');
                    setOwnerEmail('');
                    setPassword('');
                    setError(null);
                  }}
                  className="text-xs text-slate-500 hover:text-slate-400 transition-colors"
                >
                  Back to SSO
                </button>
              </div>
            </>
          )}
        </div>
      </div>
      </Reveal>
    </div>
  );
}

function getErrorMessage(error: string): string {
  const messages: Record<string, string> = {
    'oidc_failed': 'SSO authentication failed. Please try again.',
    'invalid_state': 'Authentication session expired. Please try again.',
    'flow_expired': 'Authentication session expired. Please try again.',
    'idp_unavailable': 'Identity provider is temporarily unavailable.',
    'authentication_failed': 'Authentication failed. Please try again.',
    'internal_error': 'An unexpected error occurred. Please try again.',
    'sso_unavailable': 'Single sign-on is not available. Please contact your administrator.',
    'sso_disabled': "Single sign-on is disabled. Contact your administrator.",
    'unknown_organization': "We couldn't find an organization associated with that work email. Check your email address or contact your administrator.",
    'sso_not_configured': "Single sign-on isn't configured for this organization yet. Contact your organization's administrator.",
    'invalid_email': 'Please enter a valid work email address.',
    'missing_email': 'Please enter your work email address.',
    'unauthorized': 'You are authenticated, but you are not authorized to administer this NetheriteAgent organization.',
    'unauthorized_admin': 'You are authenticated, but you are not authorized to administer this NetheriteAgent organization.',
    'not_admin': 'You are authenticated, but you are not authorized to administer this NetheriteAgent organization.',
  };
  return messages[error] || 'An error occurred during sign in.';
}
