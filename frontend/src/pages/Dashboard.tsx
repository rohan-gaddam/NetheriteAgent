import { useEffect, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Users,
  Database,
  CheckSquare,
  Zap,
  RefreshCw,
  Activity,
  ArrowRight,
  CheckCircle2,
  XCircle,
  HelpCircle,
  Clock,
} from 'lucide-react';
import { getDashboard, triggerHealthCheck, getErrorMessage } from '../api/client';
import { LoadingPage } from '../components/ui/LoadingSpinner';
import { Reveal } from '../components/ui/Reveal';
import { formatRelativeTime } from '../lib/utils';
import type { DashboardSummary, DashboardResourceHealth } from '../types';

function MetricCard({
  icon,
  label,
  value,
  variant = 'default',
}: {
  icon: React.ReactNode;
  label: string;
  value: string | number;
  variant?: 'default' | 'success' | 'danger' | 'warning';
}) {
  const colors = {
    default: 'bg-slate-500/10 text-slate-400',
    success: 'bg-emerald-500/10 text-emerald-400',
    danger: 'bg-red-500/10 text-red-400',
    warning: 'bg-amber-500/10 text-amber-400',
  };
  return (
    <div className="glass-panel rounded-xl p-5">
      <div className="flex items-center space-x-3 mb-3">
        <div className={`h-9 w-9 rounded-lg ${colors[variant]} flex items-center justify-center`}>
          {icon}
        </div>
        <span className="text-sm text-slate-400 font-medium">{label}</span>
      </div>
      <div className="text-3xl font-extrabold tracking-tight text-glow">{value}</div>
    </div>
  );
}

function HealthStatusIcon({ status }: { status: string }) {
  if (status === 'HEALTHY') return <CheckCircle2 className="h-5 w-5 text-emerald-400" />;
  if (status === 'PROBLEM') return <XCircle className="h-5 w-5 text-red-400" />;
  return <HelpCircle className="h-5 w-5 text-slate-500" />;
}

function HealthStatusLabel({ status }: { status: string }) {
  const styles: Record<string, string> = {
    HEALTHY: 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20',
    PROBLEM: 'bg-red-500/10 text-red-400 border-red-500/20',
    UNKNOWN: 'bg-slate-500/10 text-slate-400 border-slate-500/20',
  };
  const labels: Record<string, string> = {
    HEALTHY: 'Healthy',
    PROBLEM: 'Problem',
    UNKNOWN: 'Not checked',
  };
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-medium border ${styles[status] || styles.UNKNOWN}`}>
      {labels[status] || 'Unknown'}
    </span>
  );
}

function ResourceHealthRow({ resource }: { resource: DashboardResourceHealth }) {
  return (
    <div className="px-5 py-3 flex items-center justify-between border-b border-cyan-400/10 last:border-b-0">
      <div className="flex items-center space-x-3">
        <HealthStatusIcon status={resource.status} />
        <div>
          <div className="text-sm font-medium text-slate-200">{resource.resourceName}</div>
          {resource.failureReason && (
            <div className="text-xs text-slate-500 mt-0.5">{resource.failureReason}</div>
          )}
        </div>
      </div>
      <div className="text-right">
        <HealthStatusLabel status={resource.status} />
        {resource.lastCheckedAt && (
          <div className="text-[11px] text-slate-600 mt-1 flex items-center justify-end space-x-1">
            <Clock className="h-3 w-3" />
            <span>{formatRelativeTime(resource.lastCheckedAt)}</span>
          </div>
        )}
      </div>
    </div>
  );
}

function ConnectionHealth({ data }: { data: DashboardSummary['health'] }) {
  return (
    <div className="glass-panel rounded-xl overflow-hidden">
      <div className="px-5 py-4 border-b border-cyan-400/10">
        <div className="flex items-center space-x-2">
          <Activity className="h-4 w-4 text-cyan-300" />
          <h3 className="text-sm font-semibold text-white">Connection & Service Health</h3>
        </div>
      </div>
      <div className="divide-y divide-slate-800/50">
        <div className="px-5 py-3 flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <HealthStatusIcon status={data.agentConnection.status} />
            <div>
              <div className="text-sm font-medium text-slate-200">Agents to NetheriteAgent</div>
              <div className="text-xs text-slate-500">{data.agentConnection.description}</div>
            </div>
          </div>
          <HealthStatusLabel status={data.agentConnection.status} />
        </div>
        <div className="px-5 py-3 flex items-center justify-between">
          <div className="flex items-center space-x-3">
            <HealthStatusIcon status={data.endToEndExecution.status} />
            <div>
              <div className="text-sm font-medium text-slate-200">End-to-End Execution</div>
              <div className="text-xs text-slate-500">{data.endToEndExecution.description}</div>
            </div>
          </div>
          <HealthStatusLabel status={data.endToEndExecution.status} />
        </div>
        {data.resourceConnections.length > 0 && (
          <div>
            <div className="px-5 py-2 bg-slate-800/30">
              <span className="text-[11px] font-semibold text-slate-500 uppercase tracking-wider">Resource Connections</span>
            </div>
            {data.resourceConnections.map((r) => (
              <ResourceHealthRow key={r.resourceId} resource={r} />
            ))}
          </div>
        )}
        {data.resourceConnections.length === 0 && (
          <div className="px-5 py-3">
            <div className="text-sm font-medium text-slate-200">NetheriteAgent to Resources</div>
            <div className="text-xs text-slate-500 mt-0.5">No resources registered yet</div>
          </div>
        )}
      </div>
      {data.lastCheckedAt && (
        <div className="px-5 py-2.5 border-t border-cyan-400/10 bg-cyan-400/5">
          <div className="text-[11px] text-slate-600 flex items-center space-x-1">
            <Clock className="h-3 w-3" />
            <span>Last checked: {formatRelativeTime(data.lastCheckedAt)}</span>
          </div>
        </div>
      )}
    </div>
  );
}

function RecentActivity({ activities, navigate }: { activities: DashboardSummary['recentActivity']; navigate: (path: string) => void }) {
  if (activities.length === 0) {
    return (
      <div className="glass-panel rounded-xl p-5">
        <div className="flex items-center space-x-2 mb-4">
          <Zap className="h-4 w-4 text-cyan-300" />
          <h3 className="text-sm font-semibold text-white">Recent Activity</h3>
        </div>
        <div className="text-center py-8">
          <p className="text-sm text-slate-500">No activity yet</p>
          <p className="text-xs text-slate-600 mt-1">Actions performed by your AI agents will appear here.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="glass-panel rounded-xl overflow-hidden">
      <div className="px-5 py-4 border-b border-cyan-400/10 flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <Zap className="h-4 w-4 text-cyan-300" />
          <h3 className="text-sm font-semibold text-white">Recent Activity</h3>
        </div>
        <button
          onClick={() => navigate('/activity')}
          className="flex items-center space-x-1 text-xs text-cyan-300 hover:text-cyan-200 transition-colors"
        >
          <span>View all activity</span>
          <ArrowRight className="h-3 w-3" />
        </button>
      </div>
      <div className="divide-y divide-slate-800/50">
        {activities.map((a) => {
          const isAllowed = a.decision === 'ALLOW';
          const isDenied = a.decision === 'DENY';
          return (
            <div key={a.id} className="px-5 py-3 flex items-center justify-between">
              <div className="flex items-center space-x-3">
                {isDenied ? (
                  <XCircle className="h-4 w-4 text-red-400 shrink-0" />
                ) : (
                  <CheckCircle2 className="h-4 w-4 text-emerald-400 shrink-0" />
                )}
                <div>
                  <div className="text-sm font-medium text-slate-200">{a.actionType}</div>
                  <div className="text-xs text-slate-500">{a.targetResource}</div>
                </div>
              </div>
              <div className="text-right flex items-center space-x-3">
                <span className={`text-xs font-medium ${isDenied ? 'text-red-400' : isAllowed ? 'text-emerald-400' : 'text-slate-400'}`}>
                  {a.decision === 'ALLOW' ? 'Allowed' : a.decision === 'DENY' ? 'Denied' : a.decision}
                </span>
                {a.createdAt && (
                  <span className="text-[11px] text-slate-600 whitespace-nowrap">{formatRelativeTime(a.createdAt)}</span>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function ErrorDisplay({ error, onRetry }: { error: string; onRetry: () => void }) {
  const isNetwork = error.includes('fetch') || error.includes('network') || error.includes('Failed to fetch');
  const isServerError = error.includes('500') || error.includes('Internal Server Error');

  if (isNetwork) {
    return (
      <div className="bg-slate-900 border border-amber-500/20 rounded-xl p-5">
        <div className="flex items-start space-x-3">
          <HelpCircle className="h-5 w-5 text-amber-400 mt-0.5 shrink-0" />
          <div className="flex-1">
            <div className="text-sm font-medium text-amber-300">Unable to connect to NetheriteAgent</div>
            <div className="text-xs text-slate-400 mt-1">Please check that the backend is running and try again.</div>
            <button onClick={onRetry} className="mt-3 text-xs text-cyan-300 hover:text-cyan-200 transition-colors">
              Try again
            </button>
          </div>
        </div>
      </div>
    );
  }

  if (isServerError) {
    return (
      <div className="bg-slate-900 border border-amber-500/20 rounded-xl p-5">
        <div className="flex items-start space-x-3">
          <HelpCircle className="h-5 w-5 text-amber-400 mt-0.5 shrink-0" />
          <div className="flex-1">
            <div className="text-sm font-medium text-amber-300">Something went wrong</div>
            <div className="text-xs text-slate-400 mt-1">Something went wrong while loading this information. Please try again.</div>
            <button onClick={onRetry} className="mt-3 text-xs text-cyan-300 hover:text-cyan-200 transition-colors">
              Try again
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="bg-slate-900 border border-amber-500/20 rounded-xl p-5">
      <div className="flex items-start space-x-3">
        <HelpCircle className="h-5 w-5 text-amber-400 mt-0.5 shrink-0" />
        <div className="flex-1">
          <div className="text-sm font-medium text-amber-300">Something went wrong</div>
          <div className="text-xs text-slate-400 mt-1">{error}</div>
          <button onClick={onRetry} className="mt-3 text-xs text-cyan-300 hover:text-cyan-200 transition-colors">
            Try again
          </button>
        </div>
      </div>
    </div>
  );
}

export function Dashboard() {
  const navigate = useNavigate();
  const [data, setData] = useState<DashboardSummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);

  const fetchDashboard = useCallback(async () => {
    setLoading(data === null);
    try {
      const result = await getDashboard();
      setData(result);
      setError(null);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [data]);

  const handleCheckConnections = useCallback(async () => {
    setChecking(true);
    try {
      await triggerHealthCheck();
      await fetchDashboard();
    } catch {
      // silent
    } finally {
      setChecking(false);
    }
  }, [fetchDashboard]);

  useEffect(() => {
    fetchDashboard();
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  if (loading && !data) return <LoadingPage />;

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <div>
          <h1 className="text-3xl font-extrabold tracking-tight text-glow">Dashboard</h1>
          <p className="text-sm text-slate-400 mt-1">Monitor your AI agents, connected resources, approvals, and activity.</p>
        </div>
        <button
          onClick={fetchDashboard}
          disabled={loading}
          className="flex items-center space-x-2 text-xs text-slate-400 hover:text-white transition-colors"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          <span>Refresh</span>
        </button>
      </div>

      {error && !data && <ErrorDisplay error={error} onRetry={fetchDashboard} />}

      {data && (
        <div className="glass-panel rounded-2xl p-5 sm:p-6 space-y-5 shadow-glass">
          <Reveal>
          <div className="grid grid-cols-2 lg:grid-cols-5 gap-4">
            <MetricCard
              icon={<Users className="h-4 w-4" />}
              label="AI Agents"
              value={data.overview.agents}
            />
            <MetricCard
              icon={<Database className="h-4 w-4" />}
              label="Resources"
              value={data.overview.resources}
            />
            <MetricCard
              icon={<CheckSquare className="h-4 w-4" />}
              label="Pending Approvals"
              value={data.overview.pendingApprovals}
              variant={data.overview.pendingApprovals > 0 ? 'warning' : 'default'}
            />
            <MetricCard
              icon={<Zap className="h-4 w-4" />}
              label="Actions Today"
              value={data.overview.actionsToday}
            />
            <MetricCard
              icon={<XCircle className="h-4 w-4" />}
              label="Denied Actions"
              value={data.overview.deniedActions}
              variant={data.overview.deniedActions > 0 ? 'danger' : 'default'}
            />
          </div>
          </Reveal>

          <Reveal delayMs={90}>
          <ConnectionHealth data={data.health} />
          </Reveal>

          <div className="flex justify-end">
            <button
              onClick={handleCheckConnections}
              disabled={checking}
              className="flex items-center space-x-2 px-4 py-2 bg-slate-800 hover:bg-slate-700 border border-slate-700 rounded-lg text-sm text-slate-300 hover:text-white transition-colors disabled:opacity-50"
            >
              <Activity className={`h-4 w-4 ${checking ? 'animate-spin' : ''}`} />
              <span>{checking ? 'Checking...' : 'Check connections now'}</span>
            </button>
          </div>

          <Reveal delayMs={180}>
          <RecentActivity activities={data.recentActivity} navigate={navigate} />
          </Reveal>
        </div>
      )}
    </div>
  );
}
