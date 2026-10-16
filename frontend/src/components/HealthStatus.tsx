import React, { useEffect, useState } from 'react';
import { CheckCircle2, AlertCircle, RefreshCw } from 'lucide-react';

interface HealthData {
  status: string;
  components?: Record<string, { status: string }>;
}

export const HealthStatus: React.FC = () => {
  const [health, setHealth] = useState<HealthData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const fetchHealth = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await fetch('/actuator/health');
      if (res.ok) {
        const data = await res.json();
        setHealth(data);
      } else {
        setError(`Health endpoint returned status ${res.status}`);
      }
    } catch (err: any) {
      setError(err.message || 'Failed to connect to backend');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchHealth();
    const interval = setInterval(fetchHealth, 15000);
    return () => clearInterval(interval);
  }, []);

  return (
    <div className="glass-panel rounded-xl p-5 shadow-sm">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-slate-300 uppercase tracking-wider">Control Plane Subsystem Health</h3>
        <button 
          onClick={fetchHealth} 
          disabled={loading}
          className="text-xs text-slate-400 hover:text-white flex items-center space-x-1 transition-colors"
        >
          <RefreshCw className={`h-3 w-3 ${loading ? 'animate-spin' : ''}`} />
          <span>Refresh</span>
        </button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="bg-slate-950/60 border border-slate-800/80 rounded-lg p-4 flex items-center justify-between">
          <div>
            <div className="text-xs text-slate-400 font-medium">Backend Application</div>
            <div className="text-sm font-bold text-white mt-1">Spring Boot 3.3</div>
          </div>
          {health?.status === 'UP' ? (
            <CheckCircle2 className="h-5 w-5 text-emerald-400" />
          ) : (
            <AlertCircle className="h-5 w-5 text-amber-400" />
          )}
        </div>

        <div className="bg-slate-950/60 border border-slate-800/80 rounded-lg p-4 flex items-center justify-between">
          <div>
            <div className="text-xs text-slate-400 font-medium">Database (PostgreSQL)</div>
            <div className="text-sm font-bold text-white mt-1">Flyway V1 Baseline</div>
          </div>
          <span className="text-xs font-semibold px-2 py-0.5 bg-slate-800 text-slate-300 rounded">Configured</span>
        </div>

        <div className="bg-slate-950/60 border border-slate-800/80 rounded-lg p-4 flex items-center justify-between">
          <div>
            <div className="text-xs text-slate-400 font-medium">Policy Engine (OPA)</div>
            <div className="text-sm font-bold text-white mt-1">Rego v1 Evaluator</div>
          </div>
          <span className="text-xs font-semibold px-2 py-0.5 bg-slate-800 text-slate-300 rounded">Configured</span>
        </div>

        <div className="bg-slate-950/60 border border-slate-800/80 rounded-lg p-4 flex items-center justify-between">
          <div>
            <div className="text-xs text-slate-400 font-medium">State & Locks (Redis)</div>
            <div className="text-sm font-bold text-white mt-1">Concurrency Store</div>
          </div>
          <span className="text-xs font-semibold px-2 py-0.5 bg-slate-800 text-slate-300 rounded">Configured</span>
        </div>
      </div>

      {error && (
        <div className="mt-4 p-3 bg-amber-500/10 border border-amber-500/20 rounded-md text-xs text-amber-300 flex items-center space-x-2">
          <AlertCircle className="h-4 w-4 shrink-0" />
          <span>Backend not running or proxy unreachable ({error}). Start the Spring Boot backend to see live health.</span>
        </div>
      )}
    </div>
  );
};
