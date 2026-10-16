import { useEffect, useState, useCallback } from 'react';
import { FileText, Plus, AlertTriangle, RefreshCw, ChevronRight, ChevronDown, ChevronUp } from 'lucide-react';
import { listPolicies, createPolicy, createPolicyVersion, activatePolicyVersion, getErrorMessage, isApiError } from '../api/client';
import { PageHeader } from '../components/ui/PageHeader';
import { StatusBadge } from '../components/ui/StatusBadge';
import { EmptyState, LoadingCard } from '../components/ui/EmptyState';
import { LoadingSpinner } from '../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../components/ui/ConfirmDialog';
import { Reveal } from '../components/ui/Reveal';
import { formatRelativeTime } from '../lib/utils';
import type { Policy, PolicyVersion } from '../types';

function PolicyDetail({ policy, onBack, onRefresh }: { policy: Policy; onBack: () => void; onRefresh: () => void }) {
  const [expandedVersion, setExpandedVersion] = useState<string | null>(null);
  const [showNewVersion, setShowNewVersion] = useState(false);
  const [newRegoCode, setNewRegoCode] = useState('');
  const [newPrompt, setNewPrompt] = useState('');
  const [createLoading, setCreateLoading] = useState(false);
  const [confirmActivate, setConfirmActivate] = useState<PolicyVersion | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleCreateVersion = async () => {
    if (!newRegoCode.trim()) return;
    setCreateLoading(true);
    try {
      await createPolicyVersion(policy.id, { regoCode: newRegoCode.trim(), naturalLanguagePrompt: newPrompt.trim() || undefined });
      setShowNewVersion(false); setNewRegoCode(''); setNewPrompt('');
      onRefresh();
    } catch (err) {
      setError(isApiError(err) ? err.body.message : getErrorMessage(err));
    } finally {
      setCreateLoading(false);
    }
  };

  const handleActivate = async () => {
    if (!confirmActivate) return;
    try {
      await activatePolicyVersion(policy.id, confirmActivate.id);
      setConfirmActivate(null);
      onRefresh();
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center space-x-3">
        <button onClick={onBack} className="text-slate-400 hover:text-white transition-colors"><ChevronRight className="h-4 w-4 rotate-180" /></button>
        <div>
          <h1 className="text-xl font-bold text-white">{policy.name}</h1>
          <p className="text-sm text-slate-400 mt-0.5">{policy.description || 'Policy Details'}</p>
        </div>
        <div className="ml-auto"><StatusBadge label={policy.status} variant={policy.status === 'ACTIVE' ? 'success' : 'neutral'} /></div>
      </div>

      {error && (
        <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3">
          <AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
          <div className="text-sm text-red-300">{error}</div>
        </div>
      )}

      <div className="glass-panel rounded-xl p-5">
        <div className="grid grid-cols-3 gap-4 text-sm">
          <div><span className="text-slate-400">Name</span><div className="text-white mt-1">{policy.name}</div></div>
          <div><span className="text-slate-400">Status</span><div className="mt-1"><StatusBadge label={policy.status} variant={policy.status === 'ACTIVE' ? 'success' : 'neutral'} /></div></div>
          <div><span className="text-slate-400">Versions</span><div className="text-white mt-1">{policy.versions.length}</div></div>
          {policy.description && <div className="col-span-3"><span className="text-slate-400">Description</span><div className="text-white mt-1">{policy.description}</div></div>}
        </div>
      </div>

      <div className="glass-panel rounded-xl overflow-hidden">
        <div className="px-5 py-4 border-b border-cyan-400/10 flex items-center justify-between">
          <h3 className="text-sm font-semibold text-white">Versions</h3>
          <button onClick={() => setShowNewVersion(true)} className="flex items-center space-x-1.5 px-3 py-1.5 text-xs font-medium text-cyan-300 bg-cyan-400/10 border border-cyan-400/25 rounded-lg hover:bg-cyan-400/20 transition-colors">
            <Plus className="h-3.5 w-3.5" /><span>New Version</span>
          </button>
        </div>
        {policy.versions.length === 0 ? (
          <div className="p-6 text-center text-xs text-slate-500">No versions created yet</div>
        ) : (
          <div className="divide-y divide-slate-800/50">
            {[...policy.versions].sort((a, b) => b.versionNumber - a.versionNumber).map(version => (
              <div key={version.id} className="px-5 py-3">
                <div className="flex items-center justify-between cursor-pointer" onClick={() => setExpandedVersion(expandedVersion === version.id ? null : version.id)}>
                  <div className="flex items-center space-x-3">
                    <span className="text-sm font-mono text-white">v{version.versionNumber}</span>
                    <StatusBadge label={version.status} variant={version.id === policy.activeVersionId ? 'success' : 'neutral'} dot={false} />
                    {version.naturalLanguagePrompt && <span className="text-xs text-slate-500 truncate max-w-[200px]">{version.naturalLanguagePrompt}</span>}
                  </div>
                  <div className="flex items-center space-x-2">
                    {version.status !== 'ACTIVE' && (
                      <button onClick={e => { e.stopPropagation(); setConfirmActivate(version); }} className="px-2.5 py-1 text-[10px] font-medium text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 rounded hover:bg-emerald-500/20 transition-colors">
                        Activate
                      </button>
                    )}
                    {expandedVersion === version.id ? <ChevronUp className="h-4 w-4 text-slate-500" /> : <ChevronDown className="h-4 w-4 text-slate-500" />}
                  </div>
                </div>
                {expandedVersion === version.id && (
                  <div className="mt-3 p-3 bg-slate-950/60 border border-cyan-400/10 rounded-lg">
                    <div className="text-xs text-slate-400 mb-1">Rego Code</div>
                    <pre className="text-xs text-slate-300 font-mono whitespace-pre-wrap overflow-x-auto">{version.regoCode}</pre>
                    <div className="mt-2 text-xs text-slate-500">Created {formatRelativeTime(version.createdAt)}{version.activatedAt ? ` - Activated ${formatRelativeTime(version.activatedAt)}` : ''}</div>
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      {showNewVersion && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setShowNewVersion(false)} />
          <div className="relative panel-featured rounded-xl w-full max-w-2xl mx-4 p-6">
            <h3 className="text-base font-bold text-white mb-4">New Policy Version</h3>
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1.5">Rego Code *</label>
                <textarea value={newRegoCode} onChange={e => setNewRegoCode(e.target.value)} rows={8} placeholder="package netherite&#10;&#10;default allow = false&#10;&#10;allow {&#10;  input.action == &quot;READ&quot;&#10;}" className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-xs text-white font-mono placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1.5">Description (optional)</label>
                <input type="text" value={newPrompt} onChange={e => setNewPrompt(e.target.value)} placeholder="What this version does..." className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" />
              </div>
            </div>
            <div className="flex justify-end space-x-3 mt-6">
              <button onClick={() => setShowNewVersion(false)} className="px-4 py-2 text-sm font-medium text-slate-300 bg-slate-800 border border-slate-700 rounded-lg hover:bg-slate-700 transition-colors">Cancel</button>
              <button onClick={handleCreateVersion} disabled={createLoading || !newRegoCode.trim()} className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors disabled:opacity-50">
                {createLoading ? <LoadingSpinner size="sm" className="text-white" /> : 'Create Version'}
              </button>
            </div>
          </div>
        </div>
      )}

      <ConfirmDialog open={confirmActivate !== null} title="Activate Version" message={`Activate version ${confirmActivate?.versionNumber}? This will make it the active policy for authorization decisions.`} confirmLabel="Activate" variant="info" onConfirm={handleActivate} onCancel={() => setConfirmActivate(null)} />
    </div>
  );
}

export function PoliciesPage() {
  const [policies, setPolicies] = useState<Policy[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedPolicy, setSelectedPolicy] = useState<Policy | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [createName, setCreateName] = useState('');
  const [createDescription, setCreateDescription] = useState('');
  const [createLoading, setCreateLoading] = useState(false);

  const fetchPolicies = useCallback(async () => {
    setLoading(policies.length === 0);
    try {
      const data = await listPolicies();
      setPolicies(data);
      setError(null);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [policies.length]);

  useEffect(() => { fetchPolicies(); }, [fetchPolicies]);

  const handleCreate = async () => {
    if (!createName.trim()) return;
    setCreateLoading(true);
    try {
      await createPolicy({ name: createName.trim(), description: createDescription.trim() || undefined });
      setShowCreate(false); setCreateName(''); setCreateDescription('');
      fetchPolicies();
    } catch (err) {
      setError(isApiError(err) ? err.body.message : getErrorMessage(err));
    } finally {
      setCreateLoading(false);
    }
  };

  if (selectedPolicy) {
    const refreshed = policies.find(p => p.id === selectedPolicy.id) || selectedPolicy;
    return <PolicyDetail policy={refreshed} onBack={() => setSelectedPolicy(null)} onRefresh={fetchPolicies} />;
  }

  return (
    <div className="space-y-6">
      <PageHeader title="Policies" description="Define authorization rules for agent actions via OPA/Rego"
        actions={<button onClick={() => setShowCreate(true)} className="flex items-center space-x-2 px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors">
          <Plus className="h-4 w-4" /><span>New Policy</span></button>} />

      {error && <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3"><AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" /><div className="text-sm text-red-300">{error}</div><button onClick={fetchPolicies} className="ml-auto text-xs text-slate-400 hover:text-white"><RefreshCw className="h-3.5 w-3.5" /></button></div>}

      {loading ? <div className="space-y-3"><LoadingCard /><LoadingCard /></div>
        : policies.length === 0 ? (
          <EmptyState icon={<FileText className="h-10 w-10" />} title="No policies configured"
            description="Policies define authorization rules that determine whether agent actions are allowed, denied, or require human approval. Create your first policy to get started."
            action={<button onClick={() => setShowCreate(true)} className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors">New Policy</button>} />
        ) : (
          <Reveal>
          <div className="glass-panel rounded-xl overflow-hidden">
            <div className="divide-y divide-slate-800/50">
              {policies.map(policy => (
                <button key={policy.id} onClick={() => setSelectedPolicy(policy)}
                  className="w-full px-5 py-4 flex items-center justify-between hover:bg-slate-800/30 float-hover text-left">
                  <div className="flex items-center space-x-4">
                    <div className="h-10 w-10 rounded-lg bg-purple-500/10 border border-purple-400/25 flex items-center justify-center shadow-[0_0_16px_-4px_rgba(167,139,250,0.45)]"><FileText className="h-5 w-5 text-purple-300" /></div>
                    <div>
                      <div className="text-sm font-semibold text-white">{policy.name}</div>
                      <div className="text-xs text-slate-500 mt-0.5">{policy.description || 'No description'} - {policy.versions.length} version(s)</div>
                    </div>
                  </div>
                  <div className="flex items-center space-x-3">
                    <StatusBadge label={policy.status} variant={policy.status === 'ACTIVE' ? 'success' : 'neutral'} />
                    <ChevronRight className="h-4 w-4 text-slate-600" />
                  </div>
                </button>
              ))}
            </div>
          </div>
          </Reveal>
        )}

      {showCreate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setShowCreate(false)} />
          <div className="relative panel-featured rounded-xl w-full max-w-md mx-4 p-6">
            <h3 className="text-base font-bold text-white mb-4">New Policy</h3>
            <div className="space-y-4">
              <div><label className="block text-xs font-medium text-slate-400 mb-1.5">Policy Name *</label>
                <input type="text" value={createName} onChange={e => setCreateName(e.target.value)} placeholder="e.g. Payment Access Policy" autoFocus className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" /></div>
              <div><label className="block text-xs font-medium text-slate-400 mb-1.5">Description</label>
                <input type="text" value={createDescription} onChange={e => setCreateDescription(e.target.value)} placeholder="Optional description" className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" /></div>
            </div>
            <div className="flex justify-end space-x-3 mt-6">
              <button onClick={() => setShowCreate(false)} className="px-4 py-2 text-sm font-medium text-slate-300 bg-slate-800 border border-slate-700 rounded-lg hover:bg-slate-700 transition-colors">Cancel</button>
              <button onClick={handleCreate} disabled={createLoading || !createName.trim()} className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors disabled:opacity-50">
                {createLoading ? <LoadingSpinner size="sm" className="text-white" /> : 'Create Policy'}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
