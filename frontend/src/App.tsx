import { useState } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { AppLayout } from './components/AppLayout';
import { BootLoader } from './components/BootLoader';
import { LoginPage } from './pages/LoginPage';
import { AuthCallbackPage } from './pages/AuthCallbackPage';
import { Dashboard } from './pages/Dashboard';
import { AgentsPage } from './pages/AgentsPage';
import { ResourcesPage } from './pages/ResourcesPage';
import { ApprovalsPage } from './pages/ApprovalsPage';
import { AuditLogsPage } from './pages/AuditLogsPage';
import { AuthorizedPersonPage } from './pages/AuthorizedPersonPage';

function ProtectedRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function PublicRoute({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth();
  if (isAuthenticated) return <Navigate to="/" replace />;
  return <>{children}</>;
}

const INTRO_SEEN_KEY = 'netherite_intro_seen';

function AppRoutes() {
  const { authReady } = useAuth();
  // First mount this session plays the one-time visual reveal. The flag is
  // set immediately (not after the animation) so a reload never replays it.
  // Interaction is never blocked: the animation is purely presentational.
  const [intro] = useState(() => {
    try {
      if (sessionStorage.getItem(INTRO_SEEN_KEY)) return false;
      sessionStorage.setItem(INTRO_SEEN_KEY, '1');
      return true;
    } catch {
      return false;
    }
  });
  // While authentication is unresolved, show only the loader: protected
  // content must never flash before the session check settles.
  if (!authReady) return <BootLoader />;
  return (
    <div className={intro ? 'app-intro' : undefined}>
      {intro && <div className="app-bloom" aria-hidden="true" />}
      <div className="rule-luminous fixed top-0 left-0 right-0 h-[2px] z-40 pointer-events-none" aria-hidden="true" />
    <Routes>
      <Route path="/login" element={<PublicRoute><LoginPage /></PublicRoute>} />
      <Route path="/auth/callback" element={<AuthCallbackPage />} />
      <Route element={<ProtectedRoute><AppLayout /></ProtectedRoute>}>
        <Route path="/" element={<Dashboard />} />
        <Route path="/resources" element={<ResourcesPage />} />
        <Route path="/agents" element={<AgentsPage />} />
        <Route path="/approvals" element={<ApprovalsPage />} />
        <Route path="/activity" element={<AuditLogsPage />} />
        <Route path="/organization" element={<AuthorizedPersonPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
    </div>
  );
}

function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Toaster
          position="top-right"
          toastOptions={{
            style: {
              background: '#1e293b',
              color: '#f1f5f9',
              border: '1px solid #334155',
              borderRadius: '0.75rem',
              fontSize: '0.875rem',
            },
            success: {
              iconTheme: { primary: '#10b981', secondary: '#f1f5f9' },
            },
            error: {
              iconTheme: { primary: '#ef4444', secondary: '#f1f5f9' },
            },
          }}
        />
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  );
}

export default App;
