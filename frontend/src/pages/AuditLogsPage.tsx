import { useEffect, useState, useCallback } from 'react';
import { ScrollText, Search, AlertTriangle, RefreshCw } from 'lucide-react';
import { listAuditLogs, getErrorMessage } from '../api/client';
import { PageHeader } from '../components/ui/PageHeader';
import { StatusBadge, getDecisionBadgeVariant } from '../components/ui/StatusBadge';
import { EmptyState, LoadingCard } from '../components/ui/EmptyState';
import { formatRelativeTime, truncate } from '../lib/utils';
import type { AuditLog } from '../types';

export function AuditLogsPage() {
  const [logs, setLogs] = useState<AuditLog[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');

  const fetchLogs = useCallback(async () => {
    setLoading(logs.length === 0);
    try {
      const data = await listAuditLogs();
      setLogs(data);
      setError(null);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [logs.length]);

  useEffect(() => { fetchLogs(); }, [fetchLogs]);

  const filtered = logs.filter(log =>
    log.requestId.toLowerCase().includes(search.toLowerCase()) ||
    log.actionType.toLowerCase().includes(search.toLowerCase()) ||
    log.targetResource.toLowerCase().includes(search.toLowerCase()) ||
    log.decision.toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div className="space-y-6">
      <PageHeader title="Audit Logs" description="Tamper-evident audit trail of all agent actions and decisions" />

      {error && (
        <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3">
          <AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
          <div className="text-sm text-red-300">{error}</div>
          <button onClick={fetchLogs} className="ml-auto text-xs text-slate-400 hover:text-white"><RefreshCw className="h-3.5 w-3.5" /></button>
        </div>
      )}

      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-500" />
        <input type="text" value={search} onChange={e => setSearch(e.target.value)} placeholder="Search by request ID, action, resource, or decision..."
          className="w-full pl-10 pr-4 py-2.5 glass-panel rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70 transition-colors" />
      </div>

      {loading ? <div className="space-y-3"><LoadingCard /><LoadingCard /></div>
        : filtered.length === 0 ? (
          <EmptyState icon={<ScrollText className="h-10 w-10" />}
            title={search ? 'No audit logs match your search' : 'No audit logs yet'}
            description={search ? 'Try a different search term.' : 'Audit logs are automatically created when agents execute actions. They provide a tamper-evident record of all system activity.'} />
        ) : (
          <div className="glass-panel rounded-xl overflow-hidden">
            <div className="px-5 py-3 border-b border-cyan-400/10 flex items-center justify-between">
              <span className="text-xs text-slate-500">{filtered.length} record(s)</span>
              <span className="text-xs text-slate-500">Hash-chained for integrity</span>
            </div>
            <div className="divide-y divide-slate-800/50">
              {filtered.map(log => (
                <div key={log.id} className="px-5 py-3 hover:bg-slate-800/20 transition-colors">
                  <div className="flex items-center justify-between mb-1">
                    <div className="flex items-center space-x-3">
                      <span className="text-sm font-semibold text-white">{log.actionType}</span>
                      <StatusBadge label={log.decision} variant={getDecisionBadgeVariant(log.decision)} dot={false} />
                    </div>
                    <span className="text-xs text-slate-500">{formatRelativeTime(log.createdAt)}</span>
                  </div>
                  <div className="flex items-center space-x-4 text-xs text-slate-400">
                    <span>Resource: <span className="text-slate-300">{log.targetResource}</span></span>
                    <span>Request: <span className="text-slate-300 font-mono">{truncate(log.requestId, 16)}</span></span>
                    {log.executionDurationMs > 0 && <span>Duration: <span className="text-slate-300">{log.executionDurationMs}ms</span></span>}
                  </div>
                  {log.redactedParameters && Object.keys(log.redactedParameters).length > 0 && (
                    <div className="mt-2 text-xs text-slate-500">
                      Parameters: <span className="font-mono text-slate-400">{JSON.stringify(log.redactedParameters)}</span>
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}
    </div>
  );
}
