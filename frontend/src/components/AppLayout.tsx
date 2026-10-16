import { NavLink, Outlet, useLocation } from 'react-router-dom';
import {
  Shield,
  LayoutDashboard,
  CheckSquare,
  Users,
  Database,
  ScrollText,
  ShieldCheck,
  ChevronRight,
  LogOut,
} from 'lucide-react';
import { cn } from '../lib/utils';
import { useAuth } from '../auth/AuthContext';

const navItems = [
  { to: '/', icon: LayoutDashboard, label: 'Dashboard' },
  { to: '/resources', icon: Database, label: 'Resources' },
  { to: '/agents', icon: Users, label: 'Agents' },
  { to: '/approvals', icon: CheckSquare, label: 'Approvals' },
  { to: '/activity', icon: ScrollText, label: 'Activity' },
  { to: '/organization', icon: ShieldCheck, label: 'Authorized Person' },
];

function Sidebar() {
  const location = useLocation();
  const { logout } = useAuth();

  return (
    <aside className="hidden lg:flex lg:flex-col w-60 bg-[#070b1c]/70 backdrop-blur-xl border-r border-cyan-400/15 h-screen sticky top-0 shadow-[8px_0_32px_-16px_rgba(34,211,238,0.25)]">
      <div className="h-16 flex items-center px-5 border-b border-cyan-400/10">
        <div className="flex items-center space-x-3">
          <div className="h-8 w-8 rounded-lg bg-gradient-to-br from-cyan-400 via-blue-500 to-violet-500 flex items-center justify-center shadow-glow">
            <Shield className="h-4.5 w-4.5 text-white" />
          </div>
          <div>
            <span className="font-display font-bold text-sm text-white tracking-tight">NetheriteAgent</span>
          </div>
        </div>
      </div>

      <nav className="flex-1 px-3 py-4 space-y-1 overflow-y-auto">
        {navItems.map(({ to, icon: Icon, label }) => {
          const isActive = to === '/' ? location.pathname === '/' : location.pathname.startsWith(to);
          return (
            <NavLink
              key={to}
              to={to}
              className={cn(
                'flex items-center space-x-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-all duration-150',
                isActive
                  ? 'bg-cyan-400/10 text-cyan-300 border border-cyan-400/25 shadow-[0_0_16px_-6px_rgba(34,211,238,0.5)] [text-shadow:0_0_12px_rgba(103,232,249,0.55)]'
                  : 'text-slate-400 hover:text-white hover:bg-slate-800/50',
              )}
            >
              <Icon className={cn('h-4 w-4', isActive && 'text-cyan-300')} />
              <span>{label}</span>
              {isActive && <ChevronRight className="h-3 w-3 ml-auto text-cyan-300/50" />}
            </NavLink>
          );
        })}
      </nav>

      <div className="px-3 py-3 border-t border-cyan-400/10">
        <button
          onClick={logout}
          className="flex items-center space-x-3 px-3 py-2.5 rounded-lg text-sm font-medium text-slate-400 hover:text-white hover:bg-slate-800/50 transition-all duration-150 w-full"
        >
          <LogOut className="h-4 w-4" />
          <span>Sign out</span>
        </button>
      </div>
    </aside>
  );
}

function MobileHeader() {
  return (
    <div className="lg:hidden h-14 flex items-center px-4 border-b border-cyan-400/15 bg-[#070b1c]/80 backdrop-blur-xl sticky top-0 z-40">
      <div className="flex items-center space-x-2">
        <div className="h-7 w-7 rounded-lg bg-gradient-to-br from-cyan-400 via-blue-500 to-violet-500 flex items-center justify-center shadow-glow">
          <Shield className="h-3.5 w-3.5 text-white" />
        </div>
        <span className="font-display font-bold text-sm text-white">NetheriteAgent</span>
      </div>
    </div>
  );
}

function MobileNav() {
  const location = useLocation();
  const mobileItems = [
    { to: '/', icon: LayoutDashboard, label: 'Home' },
    { to: '/agents', icon: Users, label: 'Agents' },
    { to: '/resources', icon: Database, label: 'Resources' },
    { to: '/approvals', icon: CheckSquare, label: 'Approvals' },
    { to: '/activity', icon: ScrollText, label: 'Activity' },
  ];
  return (
    <div className="lg:hidden fixed bottom-0 left-0 right-0 bg-[#070b1c]/85 backdrop-blur-xl border-t border-cyan-400/15 z-50">
      <nav className="flex justify-around py-2">
        {mobileItems.map(({ to, icon: Icon, label }) => {
          const isActive = to === '/' ? location.pathname === '/' : location.pathname.startsWith(to);
          return (
            <NavLink
              key={to}
              to={to}
              className={cn(
                'flex flex-col items-center space-y-1 px-3 py-1.5 text-[10px] font-medium transition-colors',
                isActive ? 'text-cyan-300' : 'text-slate-500',
              )}
            >
              <Icon className="h-5 w-5" />
              <span>{label}</span>
            </NavLink>
          );
        })}
      </nav>
    </div>
  );
}

export function AppLayout() {
  return (
    <div className="min-h-screen bg-transparent text-slate-100 flex font-sans selection:bg-cyan-500 selection:text-white">
      <Sidebar />
      <div className="flex-1 flex flex-col min-h-screen">
        <MobileHeader />
        <main className="flex-1 max-w-6xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6 pb-24 lg:pb-6">
          <Outlet />
        </main>
        <MobileNav />
      </div>
    </div>
  );
}
