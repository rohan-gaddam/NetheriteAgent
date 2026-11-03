import { useEffect, useState, useCallback } from 'react';
import { Database, Plus, Search, ChevronRight, AlertTriangle, RefreshCw, Globe, Shield } from 'lucide-react';
import { listResources, createResource, updateResource, deactivateResource, activateResource, getErrorMessage, isApiError } from '../api/client';
import { PageHeader } from '../components/ui/PageHeader';
import { StatusBadge } from '../components/ui/StatusBadge';
import { EmptyState, LoadingCard } from '../components/ui/EmptyState';
import { LoadingSpinner } from '../components/ui/LoadingSpinner';
import { ConfirmDialog } from '../components/ui/ConfirmDialog';
import { Reveal } from '../components/ui/Reveal';
import { formatRelativeTime } from '../lib/utils';
import type { Resource, ConnectorType, SensitivityLevel } from '../types';

function ResourceDetail({ resource, onBack, onRefresh }: { resource: Resource; onBack: () => void; onRefresh: () => void }) {
  const [actionLoading, setActionLoading] = useState(false);
  const [confirmAction, setConfirmAction] = useState<'activate' | 'deactivate' | null>(null);
  const [showEditForm, setShowEditForm] = useState(false);
  const [editName, setEditName] = useState(resource.name);
  const [editBaseUrl, setEditBaseUrl] = useState(resource.baseUrl);
  const [editConnector, setEditConnector] = useState<ConnectorType>(resource.connectorType);
  const [editSensitivity, setEditSensitivity] = useState<SensitivityLevel>(resource.sensitivityLevel || 'MEDIUM');
  const [error, setError] = useState<string | null>(null);

  const handleStatusToggle = async () => {
    if (!confirmAction) return;
    setActionLoading(true);
    try {
      if (confirmAction === 'activate') {
        await activateResource(resource.id);
      } else {
        await deactivateResource(resource.id);
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
      await updateResource(resource.id, { name: editName, baseUrl: editBaseUrl, connectorType: editConnector, sensitivityLevel: editSensitivity });
      setShowEditForm(false);
      onRefresh();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActionLoading(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center space-x-3">
        <button onClick={onBack} className="text-slate-400 hover:text-white transition-colors"><ChevronRight className="h-4 w-4 rotate-180" /></button>
        <div>
          <h1 className="text-xl font-bold text-white">{resource.name}</h1>
          <p className="text-sm text-slate-400 mt-0.5">Resource Details</p>
        </div>
        <div className="ml-auto flex items-center space-x-2">
          <StatusBadge label={resource.status} variant={resource.status === 'ACTIVE' ? 'success' : 'neutral'} />
          {resource.status === 'ACTIVE' ? (
            <button onClick={() => setConfirmAction('deactivate')} className="px-3 py-1.5 text-xs font-medium text-amber-400 bg-amber-500/10 border border-amber-500/20 rounded-lg hover:bg-amber-500/20 transition-colors">Deactivate</button>
          ) : (
            <button onClick={() => setConfirmAction('activate')} className="px-3 py-1.5 text-xs font-medium text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 rounded-lg hover:bg-emerald-500/20 transition-colors">Activate</button>
          )}
        </div>
      </div>

      {error && (
        <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3">
          <AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
          <div className="text-sm text-red-300">{error}</div>
        </div>
      )}

      <div className="glass-panel rounded-xl overflow-hidden">
        <div className="px-5 py-4 border-b border-cyan-400/10 flex items-center justify-between">
          <div className="flex items-center space-x-2">
            <Database className="h-4 w-4 text-cyan-300" />
            <h3 className="text-sm font-semibold text-white">Resource Information</h3>
          </div>
          <button onClick={() => { setShowEditForm(!showEditForm); setEditName(resource.name); setEditBaseUrl(resource.baseUrl); setEditConnector(resource.connectorType); setEditSensitivity(resource.sensitivityLevel || 'MEDIUM'); }}
            className="text-xs text-slate-400 hover:text-white transition-colors">
            {showEditForm ? 'Cancel' : 'Edit'}
          </button>
        </div>
        <div className="p-5">
          {showEditForm ? (
            <div className="space-y-4">
              <div><label className="block text-xs font-medium text-slate-400 mb-1.5">Name</label>
                <input type="text" value={editName} onChange={e => setEditName(e.target.value)} className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" /></div>
              <div><label className="block text-xs font-medium text-slate-400 mb-1.5">Base URL</label>
                <input type="text" value={editBaseUrl} onChange={e => setEditBaseUrl(e.target.value)} className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white font-mono focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" /></div>
              <div><label className="block text-xs font-medium text-slate-400 mb-1.5">Connector Type</label>
                <select value={editConnector} onChange={e => setEditConnector(e.target.value as ConnectorType)} className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70">
                  <option value="HTTP_REST">HTTP REST</option><option value="AI_ERP">AI-ERP</option><option value="ERPNEXT">ERPNext</option><option value="GRAPHQL">GraphQL</option><option value="MCP">MCP</option><option value="GRPC">gRPC</option>
                </select></div>
              <div><label className="block text-xs font-medium text-slate-400 mb-1.5">Sensitivity</label>
                <select value={editSensitivity} onChange={e => setEditSensitivity(e.target.value as SensitivityLevel)} className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70">
                  <option value="LOW">Low</option><option value="MEDIUM">Medium</option><option value="HIGH">High</option><option value="CRITICAL">Critical</option>
                </select></div>
              <button onClick={handleUpdate} disabled={actionLoading} className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors disabled:opacity-50">
                {actionLoading ? <LoadingSpinner size="sm" className="text-white" /> : 'Save Changes'}</button>
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-4 text-sm">
              <div><span className="text-slate-400">Name</span><div className="text-white mt-1">{resource.name}</div></div>
              <div><span className="text-slate-400">Status</span><div className="mt-1"><StatusBadge label={resource.status} variant={resource.status === 'ACTIVE' ? 'success' : 'neutral'} /></div></div>
              <div><span className="text-slate-400">Connector Type</span><div className="text-white mt-1">{resource.connectorType}</div></div>
              <div><span className="text-slate-400">Sensitivity</span><div className="text-white mt-1">{resource.sensitivityLevel || 'MEDIUM'}</div></div>
              <div className="col-span-2"><span className="text-slate-400">Base URL</span><div className="text-white mt-1 font-mono text-xs break-all">{resource.baseUrl}</div></div>
              {resource.description && <div className="col-span-2"><span className="text-slate-400">Description</span><div className="text-white mt-1">{resource.description}</div></div>}
              <div><span className="text-slate-400">Created</span><div className="text-white mt-1">{formatRelativeTime(resource.createdAt)}</div></div>
            </div>
          )}
        </div>
      </div>

      {/* Security Notice */}
      <div className="glass-panel rounded-xl p-5">
        <div className="flex items-center space-x-2 mb-3">
          <Shield className="h-4 w-4 text-emerald-400" />
          <h3 className="text-sm font-semibold text-white">Security</h3>
        </div>
        <div className="space-y-2 text-xs text-slate-400">
          <p>This resource's registered destination is authoritative. Agent actions will be routed to <span className="text-white font-mono">{resource.baseUrl}</span>.</p>
          <p>Credentials are encrypted and never exposed through the API or this interface.</p>
        </div>
      </div>

      <ConfirmDialog open={confirmAction !== null}
        title={confirmAction === 'activate' ? 'Activate Resource' : 'Deactivate Resource'}
        message={confirmAction === 'activate' ? `Activate resource "${resource.name}"? Agents bound to this resource will be able to access it.` : `Deactivate resource "${resource.name}"? Agent access will be blocked.`}
        confirmLabel={confirmAction === 'activate' ? 'Activate' : 'Deactivate'}
        variant={confirmAction === 'activate' ? 'info' : 'warning'}
        onConfirm={handleStatusToggle} onCancel={() => setConfirmAction(null)} />
    </div>
  );
}

export function ResourcesPage() {
  const [resources, setResources] = useState<Resource[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [selectedResource, setSelectedResource] = useState<Resource | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [createName, setCreateName] = useState('');
  const [createUrl, setCreateUrl] = useState('');
  const [createConnector, setCreateConnector] = useState<ConnectorType>('HTTP_REST');
  const [createSensitivity, setCreateSensitivity] = useState<SensitivityLevel>('MEDIUM');
  const [createLoading, setCreateLoading] = useState(false);

  const fetchResources = useCallback(async () => {
    setLoading(resources.length === 0);
    try {
      const data = await listResources();
      setResources(data);
      setError(null);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [resources.length]);

  useEffect(() => { fetchResources(); }, [fetchResources]);

  const handleCreate = async () => {
    if (!createName.trim() || !createUrl.trim()) return;
    setCreateLoading(true);
    try {
      await createResource({ name: createName.trim(), baseUrl: createUrl.trim(), connectorType: createConnector, sensitivityLevel: createSensitivity });
      setShowCreate(false); setCreateName(''); setCreateUrl('');
      fetchResources();
    } catch (err) {
      setError(isApiError(err) ? err.body.message : getErrorMessage(err));
    } finally {
      setCreateLoading(false);
    }
  };

  const filtered = resources.filter(r => r.name.toLowerCase().includes(search.toLowerCase()));

  if (selectedResource) {
    const refreshed = resources.find(r => r.id === selectedResource.id) || selectedResource;
    return <ResourceDetail resource={refreshed} onBack={() => setSelectedResource(null)} onRefresh={fetchResources} />;
  }

  return (
    <div className="space-y-6">
      <PageHeader title="Resources" description="Enterprise resources protected by the control plane"
        actions={<button onClick={() => setShowCreate(true)} className="flex items-center space-x-2 px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors">
          <Plus className="h-4 w-4" /><span>Register Resource</span></button>} />

      {error && <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3"><AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" /><div className="text-sm text-red-300">{error}</div><button onClick={fetchResources} className="ml-auto text-xs text-slate-400 hover:text-white"><RefreshCw className="h-3.5 w-3.5" /></button></div>}

      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-500" />
        <input type="text" value={search} onChange={e => setSearch(e.target.value)} placeholder="Search resources..."
          className="w-full pl-10 pr-4 py-2.5 glass-panel rounded-xl text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70 transition-colors" />
      </div>

      {loading ? <div className="space-y-3"><LoadingCard /><LoadingCard /></div>
        : filtered.length === 0 ? (
          <EmptyState icon={<Database className="h-10 w-10" />}
            title={search ? 'No resources match your search' : 'No resources registered yet'}
            description={search ? 'Try a different search.' : 'Resources are enterprise systems (APIs, databases, services) that your AI agents can access. Register your first resource to get started.'}
            action={!search ? <button onClick={() => setShowCreate(true)} className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors">Register Resource</button> : undefined} />
        ) : (
          <Reveal>
          <div className="glass-panel rounded-xl overflow-hidden">
            <div className="divide-y divide-slate-800/50">
              {filtered.map(resource => (
                <button key={resource.id} onClick={() => setSelectedResource(resource)}
                  className="w-full px-5 py-4 flex items-center justify-between hover:bg-slate-800/30 float-hover text-left">
                  <div className="flex items-center space-x-4">
                    <div className="h-10 w-10 rounded-lg bg-emerald-500/10 border border-emerald-400/25 flex items-center justify-center shadow-[0_0_16px_-4px_rgba(52,211,153,0.45)]"><Globe className="h-5 w-5 text-emerald-300" /></div>
                    <div>
                      <div className="text-sm font-semibold text-white">{resource.name}</div>
                      <div className="text-xs text-slate-500 mt-0.5 font-mono">{resource.baseUrl}</div>
                    </div>
                  </div>
                  <div className="flex items-center space-x-3">
                    <StatusBadge label={resource.connectorType} variant="neutral" dot={false} />
                    <StatusBadge label={resource.status} variant={resource.status === 'ACTIVE' ? 'success' : 'neutral'} />
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
            <h3 className="text-base font-bold text-white mb-4">Register New Resource</h3>
            <div className="space-y-4">
              <div><label className="block text-xs font-medium text-slate-400 mb-1.5">Resource Name *</label>
                <input type="text" value={createName} onChange={e => setCreateName(e.target.value)} placeholder="e.g. Salesforce API" autoFocus
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" /></div>
              <div><label className="block text-xs font-medium text-slate-400 mb-1.5">Base URL *</label>
                <input type="text" value={createUrl} onChange={e => setCreateUrl(e.target.value)} placeholder="https://api.salesforce.com"
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white font-mono placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70" /></div>
              <div><label className="block text-xs font-medium text-slate-400 mb-1.5">Connector Type</label>
                <select value={createConnector} onChange={e => setCreateConnector(e.target.value as ConnectorType)}
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70">
                  <option value="HTTP_REST">HTTP REST</option><option value="AI_ERP">AI-ERP</option><option value="ERPNEXT">ERPNext</option><option value="GRAPHQL">GraphQL</option><option value="MCP">MCP</option><option value="GRPC">gRPC</option>
                </select></div>
              <div><label className="block text-xs font-medium text-slate-400 mb-1.5">Sensitivity Level</label>
                <select value={createSensitivity} onChange={e => setCreateSensitivity(e.target.value as SensitivityLevel)}
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70">
                  <option value="LOW">Low</option><option value="MEDIUM">Medium</option><option value="HIGH">High</option><option value="CRITICAL">Critical</option>
                </select></div>
            </div>
            <div className="flex justify-end space-x-3 mt-6">
              <button onClick={() => setShowCreate(false)} className="px-4 py-2 text-sm font-medium text-slate-300 bg-slate-800 border border-slate-700 rounded-lg hover:bg-slate-700 transition-colors">Cancel</button>
              <button onClick={handleCreate} disabled={createLoading || !createName.trim() || !createUrl.trim()}
                className="px-4 py-2 text-sm font-medium btn-luminous text-white rounded-lg transition-colors disabled:opacity-50">
                {createLoading ? <LoadingSpinner size="sm" className="text-white" /> : 'Register Resource'}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
