import { useState } from 'react';
import {
  Zap,
  Play,
  Search,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  RotateCcw,
  Send,
  ChevronDown,
  ChevronUp,
} from 'lucide-react';
import { executeAction, evaluatePolicy, ingestAction, getErrorMessage, isApiError } from '../api/client';
import { PageHeader } from '../components/ui/PageHeader';
import { StatusBadge, getDecisionBadgeVariant } from '../components/ui/StatusBadge';
import { LoadingSpinner } from '../components/ui/LoadingSpinner';
import { cn } from '../lib/utils';
import type { ConnectorExecutionApiResponse, PolicyEvaluationResponse, ActionIngestionResponse } from '../types';

type TabType = 'execute' | 'evaluate' | 'ingest';
type ExecutionResult = ConnectorExecutionApiResponse | PolicyEvaluationResponse | ActionIngestionResponse | null;

function TabButton({
  active,
  onClick,
  icon,
  label,
}: {
  active: boolean;
  onClick: () => void;
  icon: React.ReactNode;
  label: string;
}) {
  return (
    <button
      onClick={onClick}
      className={cn(
        'flex items-center space-x-2 px-4 py-2.5 text-sm font-medium rounded-lg transition-colors',
          active ? 'bg-cyan-400/10 text-cyan-300 border border-cyan-400/25 shadow-[0_0_16px_-6px_rgba(34,211,238,0.5)]' : 'text-slate-400 hover:text-white hover:bg-slate-800/50',
      )}
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}

function ResultCard({ result, type }: { result: ExecutionResult; type: TabType }) {
  if (!result) return null;

  if (type === 'execute') {
    const r = result as ConnectorExecutionApiResponse;
    return (
      <div className={cn(
        'glass-panel rounded-xl overflow-hidden',
        r.successful ? 'border-emerald-500/20' : 'border-red-500/20',
      )}>
        <div className={cn(
          'px-5 py-3 border-b flex items-center justify-between',
          r.successful ? 'border-emerald-500/10 bg-emerald-500/5' : 'border-red-500/10 bg-red-500/5',
        )}>
          <div className="flex items-center space-x-2">
            {r.successful ? (
              <CheckCircle2 className="h-4 w-4 text-emerald-400" />
            ) : (
              <XCircle className="h-4 w-4 text-red-400" />
            )}
            <span className="text-sm font-semibold text-white">
              {r.successful ? 'Execution Successful' : 'Execution Failed'}
            </span>
          </div>
          <StatusBadge
            label={`HTTP ${r.statusCode}`}
            variant={r.successful ? 'success' : 'danger'}
          />
        </div>
        <div className="p-5 space-y-3">
          <div className="grid grid-cols-2 gap-4 text-sm">
            <div>
              <span className="text-slate-400">Execution ID</span>
              <div className="text-white font-mono text-xs mt-1">{r.executionId}</div>
            </div>
            <div>
              <span className="text-slate-400">Duration</span>
              <div className="text-white text-xs mt-1">{r.executionDurationMillis}ms</div>
            </div>
            <div>
              <span className="text-slate-400">Resource</span>
              <div className="text-white text-xs mt-1">{r.resource}</div>
            </div>
            {r.connectorType && (
              <div>
                <span className="text-slate-400">Connector</span>
                <div className="text-white text-xs mt-1">{r.connectorType}</div>
              </div>
            )}
          </div>
          {r.errorMessage && (
            <div className="p-3 bg-red-500/5 border border-red-500/10 rounded-lg">
              <span className="text-xs text-red-300">{r.errorMessage}</span>
            </div>
          )}
          {r.responseBody && (
            <div>
              <span className="text-xs text-slate-400">Response Body</span>
              <pre className="mt-1 p-3 bg-slate-950/60 border border-cyan-400/10 rounded-lg text-xs text-slate-300 overflow-x-auto">
                {JSON.stringify(r.responseBody, null, 2)}
              </pre>
            </div>
          )}
        </div>
      </div>
    );
  }

  if (type === 'evaluate') {
    const r = result as PolicyEvaluationResponse;
    const explanation = r.explanation;
    return (
      <div className="glass-panel rounded-xl overflow-hidden">
        <div className="px-5 py-3 border-b border-cyan-400/10 flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Search className="h-4 w-4 text-cyan-300" />
            <span className="text-sm font-semibold text-white">Policy Evaluation Result</span>
          </div>
          <StatusBadge
            label={r.decision}
            variant={getDecisionBadgeVariant(r.decision)}
          />
        </div>
        <div className="p-5 space-y-4">
          <div className="grid grid-cols-3 gap-4 text-sm">
            <div>
              <span className="text-slate-400">Decision</span>
              <div className="mt-1"><StatusBadge label={r.decision} variant={getDecisionBadgeVariant(r.decision)} /></div>
            </div>
            <div>
              <span className="text-slate-400">Rules Evaluated</span>
              <div className="text-white text-xs mt-1">{r.ruleCount}</div>
            </div>
            <div>
              <span className="text-slate-400">Duration</span>
              <div className="text-white text-xs mt-1">{(r.evaluationDurationNanos / 1_000_000).toFixed(1)}ms</div>
            </div>
          </div>
          {explanation && (
            <div className="space-y-3">
              <div className="p-3 bg-slate-950/60 border border-cyan-400/10 rounded-lg">
                <span className="text-xs text-slate-400">Primary Reason</span>
                <div className="text-sm text-white mt-1">{explanation.primaryReason}</div>
              </div>
              {explanation.ruleEvaluations.length > 0 && (
                <div>
                  <span className="text-xs text-slate-400">Rule Evaluations</span>
                  <div className="mt-2 space-y-1">
                    {explanation.ruleEvaluations.map((rule, i) => (
                      <div key={i} className="text-xs text-slate-300 p-2 bg-slate-950/40 border border-slate-800/50 rounded font-mono">
                        {rule}
                      </div>
                    ))}
                  </div>
                </div>
              )}
              {explanation.contributingPolicies.length > 0 && (
                <div>
                  <span className="text-xs text-slate-400">Contributing Policies</span>
                  <div className="flex flex-wrap gap-2 mt-2">
                    {explanation.contributingPolicies.map((p, i) => (
                      <StatusBadge key={i} label={p} variant="info" dot={false} />
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    );
  }

  // Ingest
  const r = result as ActionIngestionResponse;
  return (
    <div className="glass-panel rounded-xl overflow-hidden">
      <div className="px-5 py-3 border-b border-cyan-400/10 flex items-center justify-between">
        <div className="flex items-center space-x-2">
          <Send className="h-4 w-4 text-cyan-300" />
          <span className="text-sm font-semibold text-white">Ingestion Result</span>
        </div>
        <StatusBadge
          label={r.decision}
          variant={getDecisionBadgeVariant(r.decision)}
        />
      </div>
      <div className="p-5 space-y-3">
        <div className="grid grid-cols-2 gap-4 text-sm">
          <div>
            <span className="text-slate-400">Action ID</span>
            <div className="text-white font-mono text-xs mt-1">{r.actionId}</div>
          </div>
          <div>
            <span className="text-slate-400">Status</span>
            <div className="text-white text-xs mt-1">{r.status}</div>
          </div>
        </div>
        {r.explanation && (
          <div className="p-3 bg-slate-950/60 border border-cyan-400/10 rounded-lg">
            <span className="text-xs text-slate-400">Reason</span>
            <div className="text-sm text-white mt-1">{r.explanation.primaryReason}</div>
          </div>
        )}
      </div>
    </div>
  );
}

export function ActionsPage() {
  const [activeTab, setActiveTab] = useState<TabType>('execute');
  const [action, setAction] = useState('');
  const [resource, setResource] = useState('');
  const [parameters, setParameters] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<ExecutionResult>(null);
  const [error, setError] = useState<string | null>(null);
  const [showParams, setShowParams] = useState(false);

  const handleSubmit = async () => {
    if (!action.trim() || !resource.trim()) return;

    setLoading(true);
    setError(null);
    setResult(null);

    let params: Record<string, unknown> = {};
    if (parameters.trim()) {
      try {
        params = JSON.parse(parameters);
      } catch {
        setError('Invalid JSON in parameters');
        setLoading(false);
        return;
      }
    }

    try {
      if (activeTab === 'execute') {
        const res = await executeAction({ action: action.trim(), resource: resource.trim(), parameters: params });
        setResult(res);
      } else if (activeTab === 'evaluate') {
        const res = await evaluatePolicy({ action: action.trim(), resource: resource.trim(), parameters: params });
        setResult(res);
      } else {
        const res = await ingestAction({ action: action.trim(), resource: resource.trim(), parameters: params });
        setResult(res);
      }
    } catch (err) {
      if (isApiError(err)) {
        const errResult = {
          executionId: '',
          successful: false,
          statusCode: err.status,
          executionDurationMillis: 0,
          errorMessage: err.body.message,
          resource: resource.trim(),
        } as ConnectorExecutionApiResponse;

        if (err.status === 202 && err.body.message?.includes('Ticket ID:')) {
          setResult({
            executionId: '',
            successful: false,
            statusCode: 202,
            executionDurationMillis: 0,
            errorMessage: 'HUMAN_APPROVAL required — ticket created',
            resource: resource.trim(),
          } as ConnectorExecutionApiResponse);
        } else {
          setResult(errResult);
        }
      } else {
        setError(getErrorMessage(err));
      }
    } finally {
      setLoading(false);
    }
  };

  const handleReset = () => {
    setAction('');
    setResource('');
    setParameters('');
    setResult(null);
    setError(null);
  };

  return (
    <div className="space-y-6">
      <PageHeader
        title="Actions"
        description="Submit, evaluate, and execute agent actions against protected resources"
      />

      {/* Tabs */}
      <div className="flex space-x-2 border-b border-cyan-400/10 pb-3">
        <TabButton active={activeTab === 'execute'} onClick={() => setActiveTab('execute')} icon={<Play className="h-4 w-4" />} label="Execute" />
        <TabButton active={activeTab === 'evaluate'} onClick={() => setActiveTab('evaluate')} icon={<Search className="h-4 w-4" />} label="Evaluate Policy" />
        <TabButton active={activeTab === 'ingest'} onClick={() => setActiveTab('ingest')} icon={<Send className="h-4 w-4" />} label="Ingest" />
      </div>

      {/* Form */}
      <div className="glass-panel rounded-xl p-5">
        <div className="space-y-4">
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <label className="block text-xs font-medium text-slate-400 mb-1.5">Action Type</label>
              <input
                type="text"
                value={action}
                onChange={(e) => setAction(e.target.value)}
                placeholder="e.g. PAYMENT:REFUND"
                className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70 transition-colors"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-slate-400 mb-1.5">Target Resource</label>
              <input
                type="text"
                value={resource}
                onChange={(e) => setResource(e.target.value)}
                placeholder="e.g. PAYMENT_API"
                className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70 transition-colors"
              />
            </div>
          </div>

          {/* Expandable parameters */}
          <div>
            <button
              onClick={() => setShowParams(!showParams)}
              className="flex items-center space-x-2 text-xs text-slate-400 hover:text-white transition-colors"
            >
              {showParams ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
              <span>Parameters (JSON, optional)</span>
            </button>
            {showParams && (
              <textarea
                value={parameters}
                onChange={(e) => setParameters(e.target.value)}
                placeholder='{"amount": 100.00}'
                rows={4}
                className="w-full mt-2 px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white font-mono placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70 transition-colors"
              />
            )}
          </div>

          <div className="flex items-center space-x-3">
            <button
              onClick={handleSubmit}
              disabled={!action.trim() || !resource.trim() || loading}
              className={cn(
                'flex items-center space-x-2 px-4 py-2 text-sm font-medium rounded-lg transition-colors',
                action.trim() && resource.trim() && !loading
                  ? 'btn-luminous text-white'
                  : 'bg-slate-800 text-slate-500 cursor-not-allowed',
              )}
            >
              {loading ? <LoadingSpinner size="sm" className="text-white" /> : <Zap className="h-4 w-4" />}
              <span>{activeTab === 'execute' ? 'Execute' : activeTab === 'evaluate' ? 'Evaluate' : 'Ingest'}</span>
            </button>
            <button
              onClick={handleReset}
              className="flex items-center space-x-2 px-4 py-2 text-sm font-medium text-slate-400 bg-slate-800 border border-slate-700 rounded-lg hover:bg-slate-700 transition-colors"
            >
              <RotateCcw className="h-3.5 w-3.5" />
              <span>Reset</span>
            </button>
          </div>
        </div>
      </div>

      {/* Error */}
      {error && (
        <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3">
          <AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
          <div>
            <div className="text-sm font-medium text-red-300">Request Failed</div>
            <div className="text-xs text-slate-400 mt-1">{error}</div>
          </div>
        </div>
      )}

      {/* Result */}
      <ResultCard result={result} type={activeTab} />
    </div>
  );
}
