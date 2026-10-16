import { useEffect, useState, useCallback } from 'react';
import { Users, Plus, Search, ChevronRight, Copy, CheckCircle2, AlertTriangle, RefreshCw, Key, Trash2, Link2, Database } from 'lucide-react';
import { listAgents, createAgent, updateAgent, deactivateAgent, activateAgent, listAgentKeys, createAgentKey, revokeAgentKey, listBindings, listResources, createBinding, updateBinding, deleteBinding, getErrorMessage, isApiError } from '../api/client';
import { PageHeader } from '../components/ui/PageHeader';
import { StatusBadge } from '../components/ui/StatusBadge';
import { EmptyState, LoadingCard } from '../components/ui/EmptyState';
import { LoadingSpinner } from '../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../components/ui/ConfirmDialog';
import { Reveal } from '../components/ui/Reveal';
import { formatRelativeTime } from '../lib/utils';
import type { Agent, AgentApiKey, Binding, Resource } from '../types';

function AgentStatusBadge({ status }: { status: string }) {
  return (
    <StatusBadge
      label={status}
      variant={status === 'ACTIVE' ? 'success' : 'neutral'}
    />
  );
}

function NewApiKeyDialog({ apiKey, onClose }: { apiKey: { fullApiKey: string; keyIdentifier: string } | null; onClose: () => void }) {
  const [copied, setCopied] = useState(false);

  if (!apiKey) return null;

  const handleCopy = async () => {
    await navigator.clipboard.writeText(apiKey.fullApiKey);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onClose} />
      <div className="relative bg-slate-900 border border-amber-500/30 rounded-xl shadow-2xl w-full max-w-lg mx-4 p-6">
        <div className="flex items-center space-x-3 mb-4">
          <div className="h-10 w-10 rounded-lg bg-amber-500/10 flex items-center justify-center">
            <Key className="h-5 w-5 text-amber-400" />
          </div>
          <div>
            <h3 className="text-base font-bold text-white">API Key Created</h3>
            <p className="text-xs text-amber-400 mt-0.5">Copy this key now — it will not be shown again</p>
          </div>
        </div>

        <div className="p-4 bg-slate-950/60 border border-slate-800 rounded-lg mb-4">
          <div className="text-xs text-slate-400 mb-1">Key Identifier</div>
          <div className="text-sm text-white font-mono">{apiKey.keyIdentifier}</div>
        </div>

        <div className="p-4 bg-amber-500/5 border border-amber-500/20 rounded-lg mb-4">
          <div className="text-xs text-amber-400 mb-1 font-medium">Full API Key</div>
          <div className="flex items-center space-x-2">
            <code className="flex-1 text-xs text-white font-mono break-all bg-slate-950/40 p-2 rounded">{apiKey.fullApiKey}</code>
            <button
              onClick={handleCopy}
              className="shrink-0 p-2 text-slate-400 hover:text-white bg-slate-800 rounded-lg transition-colors"
              title="Copy to clipboard"
            >
              {copied ? <CheckCircle2 className="h-4 w-4 text-emerald-400" /> : <Copy className="h-4 w-4" />}
            </button>
          </div>
        </div>

        <div className="flex justify-end">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors"
          >
            I have saved this key
          </button>
        </div>
      </div>
    </div>
  );
}

function AgentDetail({ agent, onBack, onRefresh }: { agent: Agent; onBack: () => void; onRefresh: () => void }) {
  const [keys, setKeys] = useState<AgentApiKey[]>([]);
  const [keysLoading, setKeysLoading] = useState(true);
  const [newKey, setNewKey] = useState<{ fullApiKey: string; keyIdentifier: string } | null>(null);
  const [actionLoading, setActionLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmAction, setConfirmAction] = useState<'activate' | 'deactivate' | null>(null);
  const [bindings, setBindings] = useState<Binding[]>([]);
  const [resources, setResources] = useState<Resource[]>([]);
  const [bindingsLoading, setBindingsLoading] = useState(true);
  const [showBindingCreate, setShowBindingCreate] = useState(false);
  const [createResourceId, setCreateResourceId] = useState('');
  const [createActions, setCreateActions] = useState('');
  const [editingBinding, setEditingBinding] = useState<string | null>(null);
  const [editActions, setEditActions] = useState('');
  const [confirmDeleteBinding, setConfirmDeleteBinding] = useState<Binding | null>(null);
  const [showEditForm, setShowEditForm] = useState(false);
  const [editName, setEditName] = useState(agent.name);
  const [editDescription, setEditDescription] = useState(agent.description || '');
  const [editTrustTier, setEditTrustTier] = useState(agent.trustTier || 'TIER_1');

  const fetchKeys = useCallback(async () => {
    try {
      const data = await listAgentKeys(agent.id);
      setKeys(data);
    } catch {
      setError('Failed to load API keys');
    } finally {
      setKeysLoading(false);
    }
  }, [agent.id]);

  useEffect(() => { fetchKeys(); }, [fetchKeys]);

  const handleStatusToggle = async () => {
    if (!confirmAction) return;
    setActionLoading(true);
    try {
      if (confirmAction === 'activate') {
        await activateAgent(agent.id);
      } else {
        await deactivateAgent(agent.id);
      }
      onRefresh();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActionLoading(false);
      setConfirmAction(null);
    }
  };

  const handleUpdate = async () => {
    setActionLoading(true);
    setError(null);
    try {
      await updateAgent(agent.id, { name: editName, description: editDescription || undefined, trustTier: editTrustTier });
      setShowEditForm(false);
      onRefresh();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActionLoading(false);
    }
  };

  const handleCreateKey = async () => {
    setActionLoading(true);
    setError(null);
    try {
      const result = await createAgentKey(agent.id, 'Generated via Control Plane');
      setNewKey(result);
      fetchKeys();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActionLoading(false);
    }
  };

  const fetchBindings = useCallback(async () => {
    try {
      const [allBindings, allResources] = await Promise.all([listBindings(), listResources()]);
      setBindings(allBindings.filter(b => b.agentId === agent.id));
      setResources(allResources);
      setError(null);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setBindingsLoading(false);
    }
  }, [agent.id]);

  useEffect(() => { fetchBindings(); }, [fetchBindings]);

  const handleCreateBinding = async () => {
    if (!createResourceId) return;
    setActionLoading(true);
    setError(null);
    try {
      const actions = createActions.split(',').map(a => a.trim()).filter(Boolean);
      await createBinding({ agentId: agent.id, resourceId: createResourceId, allowedActions: actions });
      setShowBindingCreate(false); setCreateResourceId(''); setCreateActions('');
      fetchBindings();
    } catch (err) {
      setError(isApiError(err) ? err.body.message : getErrorMessage(err));
    } finally {
      setActionLoading(false);
    }
  };

  const handleUpdateBindingActions = async (binding: Binding) => {
    try {
      const actions = editActions.split(',').map(a => a.trim()).filter(Boolean);
      await updateBinding(binding.id, { allowedActions: actions });
      setEditingBinding(null);
      fetchBindings();
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  const handleDeleteBinding = async () => {
    if (!confirmDeleteBinding) return;
    try {
      await deleteBinding(confirmDeleteBinding.id);
      setConfirmDeleteBinding(null);
      fetchBindings();
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  const handleRevokeKey = async (keyId: string) => {
    setActionLoading(true);
    try {
      await revokeAgentKey(agent.id, keyId);
      fetchKeys();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActionLoading(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center space-x-3">
        <button onClick={onBack} className="text-slate-400 hover:text-white transition-colors">
          <ChevronRight className="h-4 w-4 rotate-180" />
        </button>
        <div>
          <h1 className="text-xl font-bold text-white">{agent.name}</h1>
          <p className="text-sm text-slate-400 mt-0.5">Agent Details</p>
        </div>
        <div className="ml-auto flex items-center space-x-2">
          <AgentStatusBadge status={agent.status} />
          {agent.status === 'ACTIVE' ? (
            <button
              onClick={() => setConfirmAction('deactivate')}
              className="px-3 py-1.5 text-xs font-medium text-amber-400 bg-amber-500/10 border border-amber-500/20 rounded-lg hover:bg-amber-500/20 transition-colors"
            >
              Deactivate
            </button>
          ) : (
            <button
              onClick={() => setConfirmAction('activate')}
              className="px-3 py-1.5 text-xs font-medium text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 rounded-lg hover:bg-emerald-500/20 transition-colors"
            >
              Activate
            </button>
          )}
        </div>
      </div>

      {error && (
        <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3">
          <AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
          <div className="text-sm text-red-300">{error}</div>
        </div>
      )}

      {/* Agent Info */}
      <div className="glass-panel rounded-xl overflow-hidden">
        <div className="px-5 py-4 border-b border-cyan-400/10 flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Users className="h-4 w-4 text-cyan-300" />
            <h3 className="text-sm font-semibold text-white">Agent Information</h3>
          </div>
          <button
            onClick={() => { setShowEditForm(!showEditForm); setEditName(agent.name); setEditDescription(agent.description || ''); setEditTrustTier(agent.trustTier || 'TIER_1'); }}
            className="text-xs text-slate-400 hover:text-white transition-colors"
          >
            {showEditForm ? 'Cancel' : 'Edit'}
          </button>
        </div>
        <div className="p-5">
          {showEditForm ? (
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1.5">Name</label>
                <input type="text" value={editName} onChange={e => setEditName(e.target.value)}
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1.5">Description</label>
                <input type="text" value={editDescription} onChange={e => setEditDescription(e.target.value)}
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1.5">Trust Tier</label>
                <select value={editTrustTier} onChange={e => setEditTrustTier(e.target.value)}
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70">
                  <option value="TIER_1">TIER_1</option>
                  <option value="TIER_2">TIER_2</option>
                  <option value="TIER_3">TIER_3</option>
                </select>
              </div>
              <button onClick={handleUpdate} disabled={actionLoading || !editName.trim()}
                className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors disabled:opacity-50">
                {actionLoading ? <LoadingSpinner size="sm" className="text-white" /> : 'Save Changes'}
              </button>
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div><span className="text-slate-400">Name</span><div className="text-white mt-1">{agent.name}</div></div>
              <div><span className="text-slate-400">Status</span><div className="mt-1"><AgentStatusBadge status={agent.status} /></div></div>
              <div><span className="text-slate-400">Trust Tier</span><div className="text-white mt-1">{agent.trustTier || 'TIER_1'}</div></div>
              <div><span className="text-slate-400">Created</span><div className="text-white mt-1">{formatRelativeTime(agent.createdAt)}</div></div>
              {agent.description && <div className="col-span-2"><span className="text-slate-400">Description</span><div className="text-white mt-1">{agent.description}</div></div>}
            </div>
          )}
        </div>
      </div>

      {/* API Keys */}
      <div className="glass-panel rounded-xl overflow-hidden">
        <div className="px-5 py-4 border-b border-cyan-400/10 flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Key className="h-4 w-4 text-amber-400" />
            <h3 className="text-sm font-semibold text-white">API Keys</h3>
          </div>
          <button onClick={handleCreateKey} disabled={actionLoading}
            className="flex items-center space-x-1.5 px-3 py-1.5 text-xs font-medium text-cyan-300 bg-cyan-400/10 border border-cyan-400/25 rounded-lg hover:bg-cyan-400/20 transition-colors disabled:opacity-50">
            <Plus className="h-3.5 w-3.5" />
            <span>Create Key</span>
          </button>
        </div>
        {keysLoading ? (
          <div className="p-8 flex justify-center"><LoadingSpinner size="md" /></div>
        ) : keys.length === 0 ? (
          <div className="p-6 text-center text-xs text-slate-500">No API keys</div>
        ) : (
          <div className="divide-y divide-slate-800/50">
            {keys.map(key => (
              <div key={key.keyId} className="px-5 py-3 flex items-center justify-between">
                <div className="flex items-center space-x-3">
                  <Key className="h-4 w-4 text-slate-500" />
                  <div>
                    <div className="text-sm text-white font-mono">{key.keyIdentifier}</div>
                    <div className="text-xs text-slate-500">{key.description} · Created {formatRelativeTime(key.createdAt)}</div>
                  </div>
                </div>
                <div className="flex items-center space-x-2">
                  <StatusBadge label={key.valid ? 'Active' : 'Revoked'} variant={key.valid ? 'success' : 'neutral'} />
                  {key.valid && (
                    <button onClick={() => handleRevokeKey(key.keyId)} disabled={actionLoading}
                      className="p-1.5 text-slate-500 hover:text-red-400 transition-colors" title="Revoke">
                      <Trash2 className="h-3.5 w-3.5" />
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Access — resource bindings for this agent. An empty allow-list denies
          everything (never "allow all"); bindings here only narrow access. */}
      <div className="glass-panel rounded-xl overflow-hidden">
        <div className="px-5 py-4 border-b border-cyan-400/10 flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Link2 className="h-4 w-4 text-emerald-400" />
            <h3 className="text-sm font-semibold text-white">Access</h3>
          </div>
          <button onClick={() => setShowBindingCreate(true)}
            className="flex items-center space-x-1.5 px-3 py-1.5 text-xs font-medium text-cyan-300 bg-cyan-400/10 border border-cyan-400/25 rounded-lg hover:bg-cyan-400/20 transition-colors">
            <Plus className="h-3.5 w-3.5" />
            <span>New Binding</span>
          </button>
        </div>
        {bindingsLoading ? (
          <div className="p-8 flex justify-center"><LoadingSpinner size="md" /></div>
        ) : bindings.length === 0 ? (
          <div className="p-6 text-center text-xs text-slate-500">No resource bindings — this agent cannot access any resource.</div>
        ) : (
          <div className="divide-y divide-slate-800/50">
            {bindings.map(binding => {
              const resource = resources.find(r => r.id === binding.resourceId);
              const isEditing = editingBinding === binding.id;
              return (
                <div key={binding.id} className="px-5 py-3">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center space-x-3">
                      <Database className="h-4 w-4 text-slate-500" />
                      <span className="text-sm text-white font-medium">{resource?.name || binding.resourceId.slice(0, 8)}</span>
                      <StatusBadge label={binding.active ? 'Active' : 'Inactive'} variant={binding.active ? 'success' : 'neutral'} />
                    </div>
                    <div className="flex items-center space-x-2">
                      {!isEditing && (
                        <button onClick={() => { setEditingBinding(binding.id); setEditActions(binding.allowedActions.join(', ')); }}
                          className="text-xs text-slate-400 hover:text-white transition-colors">Edit Actions</button>
                      )}
                      <button onClick={() => setConfirmDeleteBinding(binding)}
                        className="p-1.5 text-slate-500 hover:text-red-400 transition-colors" title="Deactivate">
                        <Trash2 className="h-3.5 w-3.5" />
                      </button>
                    </div>
                  </div>
                  {isEditing ? (
                    <div className="mt-3 flex items-center space-x-2">
                      <input type="text" value={editActions} onChange={e => setEditActions(e.target.value)} placeholder="CRM.READ, CRM.UPDATE"
                        className="flex-1 px-3 py-1.5 bg-slate-950/60 border border-slate-700 rounded-lg text-xs text-white font-mono focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" />
                      <button onClick={() => handleUpdateBindingActions(binding)} className="px-3 py-1.5 text-xs font-medium btn-luminous text-white rounded-lg transition-colors">Save</button>
                      <button onClick={() => setEditingBinding(null)} className="px-3 py-1.5 text-xs font-medium text-slate-400 hover:text-white transition-colors">Cancel</button>
                    </div>
                  ) : (
                    <div className="mt-2 flex flex-wrap gap-1.5">
                      {binding.allowedActions.length === 0 ? (
                        <span className="text-xs text-slate-500 italic">No actions allowed (deny all)</span>
                      ) : binding.allowedActions.map(action => (
                        <span key={action} className="px-2 py-0.5 text-xs font-mono bg-slate-800 text-slate-300 rounded border border-slate-700">{action}</span>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {showBindingCreate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setShowBindingCreate(false)} />
          <div className="relative panel-featured rounded-xl w-full max-w-md mx-4 p-6">
            <h3 className="text-base font-bold text-white mb-4">New Access Binding</h3>
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1.5">Resource *</label>
                <select value={createResourceId} onChange={e => setCreateResourceId(e.target.value)}
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70">
                  <option value="">Select a resource...</option>
                  {resources.filter(r => r.status === 'ACTIVE').map(r => <option key={r.id} value={r.id}>{r.name}</option>)}
                </select>
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1.5">Allowed Actions (comma-separated, empty denies all)</label>
                <input type="text" value={createActions} onChange={e => setCreateActions(e.target.value)} placeholder="CRM.READ, CRM.UPDATE"
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-xs text-white font-mono placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" />
              </div>
            </div>
            <div className="flex justify-end space-x-3 mt-6">
              <button onClick={() => setShowBindingCreate(false)} className="px-4 py-2 text-sm font-medium text-slate-300 bg-slate-800 border border-slate-700 rounded-lg hover:bg-slate-700 transition-colors">Cancel</button>
              <button onClick={handleCreateBinding} disabled={actionLoading || !createResourceId}
                className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors disabled:opacity-50">
                {actionLoading ? <LoadingSpinner size="sm" className="text-white" /> : 'Create Binding'}</button>
            </div>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirmDeleteBinding !== null}
        title="Deactivate Binding"
        message="Deactivate this agent's access to the selected resource? The agent will no longer be able to act on it."
        confirmLabel="Deactivate" variant="warning" onConfirm={handleDeleteBinding} onCancel={() => setConfirmDeleteBinding(null)} />

      <NewApiKeyDialog apiKey={newKey} onClose={() => setNewKey(null)} />

      <ConfirmDialog
        open={confirmAction !== null}
        title={confirmAction === 'activate' ? 'Activate Agent' : 'Deactivate Agent'}
        message={confirmAction === 'activate'
          ? `Activate agent "${agent.name}"? It will be able to authenticate and execute actions.`
          : `Deactivate agent "${agent.name}"? It will no longer be able to authenticate.`}
        confirmLabel={confirmAction === 'activate' ? 'Activate' : 'Deactivate'}
        variant={confirmAction === 'activate' ? 'info' : 'warning'}
        onConfirm={handleStatusToggle}
        onCancel={() => setConfirmAction(null)}
      />
    </div>
  );
}

export function AgentsPage() {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [selectedAgent, setSelectedAgent] = useState<Agent | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [createName, setCreateName] = useState('');
  const [createDescription, setCreateDescription] = useState('');
  const [createLoading, setCreateLoading] = useState(false);
  const [newAgentKey, setNewAgentKey] = useState<{ fullApiKey: string; keyIdentifier: string } | null>(null);

  const fetchAgents = useCallback(async () => {
    setLoading(agents.length === 0);
    try {
      const data = await listAgents();
      setAgents(data);
      setError(null);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [agents.length]);

  useEffect(() => { fetchAgents(); }, [fetchAgents]);

  const handleCreate = async () => {
    if (!createName.trim()) return;
    setCreateLoading(true);
    try {
      const result = await createAgent({ name: createName.trim(), description: createDescription.trim() || undefined });
      if (result.fullApiKey && result.keyIdentifier) {
        setNewAgentKey({ fullApiKey: result.fullApiKey, keyIdentifier: result.keyIdentifier });
      }
      setShowCreate(false);
      setCreateName('');
      setCreateDescription('');
      fetchAgents();
    } catch (err) {
      setError(isApiError(err) ? err.body.message : getErrorMessage(err));
    } finally {
      setCreateLoading(false);
    }
  };

  const filteredAgents = agents.filter(a =>
    a.name.toLowerCase().includes(search.toLowerCase()) ||
    (a.description && a.description.toLowerCase().includes(search.toLowerCase()))
  );

  if (selectedAgent) {
    const refreshed = agents.find(a => a.id === selectedAgent.id) || selectedAgent;
    return <AgentDetail agent={refreshed} onBack={() => setSelectedAgent(null)} onRefresh={fetchAgents} />;
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Agents"
        description="Manage AI agents registered with your control plane"
        actions={
          <button
            onClick={() => setShowCreate(true)}
            className="flex items-center space-x-2 px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors"
          >
            <Plus className="h-4 w-4" />
            <span>Register Agent</span>
          </button>
        }
      />

      {error && (
        <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3">
          <AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
          <div className="text-sm text-red-300">{error}</div>
          <button onClick={fetchAgents} className="ml-auto text-xs text-slate-400 hover:text-white"><RefreshCw className="h-3.5 w-3.5" /></button>
        </div>
      )}

      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-500" />
        <input
          type="text"
          value={search}
          onChange={e => setSearch(e.target.value)}
          placeholder="Search agents..."
          className="w-full pl-10 pr-4 py-2.5 glass-panel rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70 transition-colors"
        />
      </div>

      {loading ? (
        <div className="space-y-3"><LoadingCard /><LoadingCard /></div>
      ) : filteredAgents.length === 0 ? (
        <EmptyState
          icon={<Users className="h-10 w-10" />}
          title={search ? 'No agents match your search' : 'No agents registered yet'}
          description={search ? 'Try a different search term.' : 'Agents are AI systems that access your enterprise resources through the control plane. Register your first agent to get started.'}
          action={!search ? (
            <button onClick={() => setShowCreate(true)} className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors">
              Register Agent
            </button>
          ) : undefined}
        />
      ) : (
        <Reveal>
        <div className="glass-panel rounded-xl overflow-hidden">
          <div className="divide-y divide-slate-800/50">
            {filteredAgents.map(agent => (
              <button
                key={agent.id}
                onClick={() => setSelectedAgent(agent)}
                className="w-full px-5 py-4 flex items-center justify-between hover:bg-slate-800/30 float-hover text-left"
              >
                <div className="flex items-center space-x-4">
                  <div className="h-10 w-10 rounded-lg bg-cyan-400/10 border border-cyan-400/30 flex items-center justify-center shadow-[0_0_16px_-4px_rgba(34,211,238,0.5)]">
                    <Users className="h-5 w-5 text-cyan-300" />
                  </div>
                  <div>
                    <div className="text-sm font-semibold text-white">{agent.name}</div>
                    <div className="text-xs text-slate-500 mt-0.5">
                      {agent.description || 'No description'} · {agent.trustTier || 'TIER_1'}
                    </div>
                  </div>
                </div>
                <div className="flex items-center space-x-3">
                  <AgentStatusBadge status={agent.status} />
                  <ChevronRight className="h-4 w-4 text-slate-600" />
                </div>
              </button>
            ))}
          </div>
        </div>
        </Reveal>
      )}

      {/* Create Dialog */}
      {showCreate && (
        <div className="fixed inset-0 z-50 flex items-center justify-center">
          <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setShowCreate(false)} />
          <div className="relative panel-featured rounded-xl w-full max-w-md mx-4 p-6">
            <h3 className="text-base font-bold text-white mb-4">Register New Agent</h3>
            <div className="space-y-4">
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1.5">Agent Name *</label>
                <input type="text" value={createName} onChange={e => setCreateName(e.target.value)} placeholder="e.g. SalesBot-01"
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" autoFocus />
              </div>
              <div>
                <label className="block text-xs font-medium text-slate-400 mb-1.5">Description</label>
                <input type="text" value={createDescription} onChange={e => setCreateDescription(e.target.value)} placeholder="Optional description"
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" />
              </div>
            </div>
            <div className="flex justify-end space-x-3 mt-6">
              <button onClick={() => setShowCreate(false)} className="px-4 py-2 text-sm font-medium text-slate-300 bg-slate-800 border border-slate-700 rounded-lg hover:bg-slate-700 transition-colors">Cancel</button>
              <button onClick={handleCreate} disabled={createLoading || !createName.trim()}
                className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors disabled:opacity-50">
                {createLoading ? <LoadingSpinner size="sm" className="text-white" /> : 'Register Agent'}
              </button>
            </div>
          </div>
        </div>
      )}

      <NewApiKeyDialog apiKey={newAgentKey} onClose={() => setNewAgentKey(null)} />
    </div>
  );
}
