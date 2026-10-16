import { useEffect, useState, useCallback } from 'react';
import {
  Activity,
  Database,
  Server,
  Shield,
  RefreshCw,
  CheckCircle2,
  XCircle,
  AlertCircle,
  Zap,
  HardDrive,
  Network,
} from 'lucide-react';
import { getHealth, getErrorMessage } from '../api/client';
import { PageHeader } from '../components/ui/PageHeader';
import { StatusBadge } from '../components/ui/StatusBadge';
import { LoadingPage } from '../components/ui/LoadingSpinner';
import { formatRelativeTime } from '../lib/utils';
import type { HealthResponse, HealthComponent } from '../types';

const COMPONENT_ICONS: Record<string, React.ReactNode> = {
  db: <Database className="h-4 w-4" />,
  database: <Database className="h-4 w-4" />,
  postgresql: <Database className="h-4 w-4" />,
  redis: <HardDrive className="h-4 w-4" />,
  disk: <HardDrive className="h-4 w-4" />,
  health: <Activity className="h-4 w-4" />,
  ping: <Network className="h-4 w-4" />,
  opa: <Shield className="h-4 w-4" />,
};

function getComponentIcon(name: string): React.ReactNode {
  const lower = name.toLowerCase();
  for (const [key, icon] of Object.entries(COMPONENT_ICONS)) {
    if (lower.includes(key)) return icon;
  }
  return <Server className="h-4 w-4" />;
}

function ComponentCard({ name, component }: { name: string; component: HealthComponent }) {
  const isUp = component.status === 'UP';
  return (
    <div className="glass-panel rounded-xl p-4 hover:border-cyan-400/30 transition-colors">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center space-x-3">
          <div className={`h-8 w-8 rounded-lg flex items-center justify-center ${isUp ? 'bg-emerald-500/10 text-emerald-400' : 'bg-red-500/10 text-red-400'}`}>
            {getComponentIcon(name)}
          </div>
          <div>
            <h4 className="text-sm font-semibold text-white capitalize">{name.replace(/_/g, ' ')}</h4>
            <div className="text-xs text-slate-500 mt-0.5">
              {isUp ? 'Operational' : 'Issue Detected'}
            </div>
          </div>
        </div>
        <StatusBadge label={component.status} variant={isUp ? 'success' : 'danger'} />
      </div>

      {component.details && Object.keys(component.details).length > 0 && (
        <div className="mt-3 space-y-1.5">
          {Object.entries(component.details).map(([key, value]) => (
            <div key={key} className="flex items-center justify-between text-xs">
              <span className="text-slate-400">{key.replace(/_/g, ' ')}</span>
              <span className="text-slate-300 font-mono">{String(value)}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function SystemStatus() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lastChecked, setLastChecked] = useState<Date | null>(null);

  const fetchHealth = useCallback(async () => {
    setLoading(health === null);
    try {
      const data = await getHealth();
      setHealth(data);
      setLastChecked(new Date());
      setError(null);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [health]);

  useEffect(() => {
    fetchHealth();
    const interval = setInterval(fetchHealth, 15000);
    return () => clearInterval(interval);
  }, [fetchHealth]);

  if (loading && !health) return <LoadingPage />;

  const components = health?.components || {};
  const componentEntries = Object.entries(components);
  const upCount = componentEntries.filter(([, c]) => c.status === 'UP').length;
  const totalCount = componentEntries.length;

  return (
    <div className="space-y-6">
      <div className="flex items-start justify-between">
        <PageHeader
          title="System Status"
          description="Control plane health and infrastructure monitoring"
        />
        <button
          onClick={fetchHealth}
          disabled={loading}
          className="flex items-center space-x-2 text-xs text-slate-400 hover:text-white transition-colors"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${loading ? 'animate-spin' : ''}`} />
          <span>Refresh</span>
        </button>
      </div>

      {/* Overall Status */}
      <div className="glass-panel rounded-xl p-5">
        <div className="flex items-center justify-between">
          <div className="flex items-center space-x-4">
            <div className={`h-12 w-12 rounded-xl flex items-center justify-center ${health?.status === 'UP' ? 'bg-emerald-500/10 text-emerald-400' : 'bg-red-500/10 text-red-400'}`}>
              {health?.status === 'UP' ? <CheckCircle2 className="h-6 w-6" /> : <XCircle className="h-6 w-6" />}
            </div>
            <div>
              <h3 className="text-lg font-bold text-white">
                {health?.status === 'UP' ? 'All Systems Operational' : 'System Issues Detected'}
              </h3>
              <p className="text-sm text-slate-400 mt-0.5">
                {health?.status === 'UP'
                  ? `${upCount}/${totalCount} subsystems operational`
                  : 'Some subsystems are experiencing issues'}
              </p>
            </div>
          </div>
          {lastChecked && (
            <div className="text-right hidden sm:block">
              <div className="text-xs text-slate-500">Last checked</div>
              <div className="text-sm text-slate-300 mt-0.5">{formatRelativeTime(lastChecked.toISOString())}</div>
            </div>
          )}
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="bg-amber-500/5 border border-amber-500/20 rounded-xl p-4 flex items-start space-x-3">
          <AlertCircle className="h-4 w-4 text-amber-400 mt-0.5 shrink-0" />
          <div>
            <div className="text-sm font-medium text-amber-300">Backend Not Reachable</div>
            <div className="text-xs text-slate-400 mt-1">{error}. Ensure the Spring Boot backend is running.</div>
          </div>
        </div>
      )}

      {/* Component Grid */}
      {componentEntries.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {componentEntries.map(([name, component]) => (
            <ComponentCard key={name} name={name} component={component} />
          ))}
        </div>
      ) : (
        <div className="glass-panel rounded-xl p-8 text-center">
          <Activity className="h-8 w-8 text-slate-600 mx-auto mb-3" />
          <h4 className="text-sm font-medium text-slate-300">No Component Details</h4>
          <p className="text-xs text-slate-500 mt-1">Backend may be starting up. Health data will appear once subsystems initialize.</p>
        </div>
      )}

      {/* Architecture Info */}
      <div className="glass-panel rounded-xl p-5">
        <h3 className="text-sm font-semibold text-white mb-4">Infrastructure</h3>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="text-center p-3 bg-slate-950/40 rounded-lg">
            <Database className="h-5 w-5 text-slate-400 mx-auto mb-2" />
            <div className="text-xs text-slate-400">Database</div>
            <div className="text-sm font-medium text-white mt-1">PostgreSQL 16</div>
          </div>
          <div className="text-center p-3 bg-slate-950/40 rounded-lg">
            <HardDrive className="h-5 w-5 text-slate-400 mx-auto mb-2" />
            <div className="text-xs text-slate-400">Cache/Locks</div>
            <div className="text-sm font-medium text-white mt-1">Redis</div>
          </div>
          <div className="text-center p-3 bg-slate-950/40 rounded-lg">
            <Shield className="h-5 w-5 text-slate-400 mx-auto mb-2" />
            <div className="text-xs text-slate-400">Policy Engine</div>
            <div className="text-sm font-medium text-white mt-1">OPA / Rego</div>
          </div>
          <div className="text-center p-3 bg-slate-950/40 rounded-lg">
            <Zap className="h-5 w-5 text-slate-400 mx-auto mb-2" />
            <div className="text-xs text-slate-400">Framework</div>
            <div className="text-sm font-medium text-white mt-1">Spring Boot 3.3</div>
          </div>
        </div>
      </div>
    </div>
  );
}
