import { useState, useEffect, useCallback, useRef } from 'react';
import {
  Search,
  CheckCircle2,
  XCircle,
  AlertTriangle,
  ShieldCheck,
  ChevronRight,
  Clock,
  RefreshCw,
  Wifi,
  WifiOff,
} from 'lucide-react';
import {
  listApprovals,
  getApprovalTicket,
  approveTicket,
  rejectTicket,
  getErrorMessage,
  isApiError,
} from '../api/client';
import { PageHeader } from '../components/ui/PageHeader';
import { StatusBadge, getApprovalBadgeVariant } from '../components/ui/StatusBadge';
import { LoadingSpinner } from '../components/ui/LoadingSpinner';
import { EmptyState } from '../components/ui/EmptyState';
import { ConfirmDialog } from '../components/ui/ConfirmDialog';
import { formatRelativeTime, truncate, cn } from '../lib/utils';
import { useApprovalSSE } from '../hooks/useApprovalSSE';
import toast from 'react-hot-toast';
import type { PendingApprovalTicket, ApprovalTicketResponse } from '../types';

type Tab = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED';

const TABS: { key: Tab; label: string }[] = [
  { key: 'PENDING', label: 'Pending' },
  { key: 'APPROVED', label: 'Approved' },
  { key: 'REJECTED', label: 'Rejected' },
  { key: 'EXPIRED', label: 'Expired' },
];

function ApprovalDetail({
  ticket,
  onBack,
  onRefresh,
}: {
  ticket: ApprovalTicketResponse;
  onBack: () => void;
  onRefresh: () => void;
}) {
  const [notes, setNotes] = useState('');
  const [actionLoading, setActionLoading] = useState(false);
  const [confirmAction, setConfirmAction] = useState<'approve' | 'reject' | null>(null);
  const [actionResult, setActionResult] = useState<{ type: 'success' | 'error'; message: string } | null>(null);

  const handleAction = async () => {
    if (!confirmAction) return;
    setActionLoading(true);

    try {
      if (confirmAction === 'approve') {
        await approveTicket(ticket.ticketId, undefined, notes || undefined);
      } else {
        await rejectTicket(ticket.ticketId, notes || undefined);
      }
      setActionResult({
        type: 'success',
        message: confirmAction === 'approve' ? 'Ticket approved successfully' : 'Ticket rejected',
      });
      onRefresh();
    } catch (err) {
      setActionResult({
        type: 'error',
        message: isApiError(err) ? err.body.message : getErrorMessage(err),
      });
    } finally {
      setActionLoading(false);
      setConfirmAction(null);
      setNotes('');
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center space-x-3">
        <button onClick={onBack} className="text-slate-400 hover:text-white transition-colors">
          <ChevronRight className="h-4 w-4 rotate-180" />
        </button>
        <div>
          <h1 className="text-xl font-bold text-white">Approval Ticket</h1>
          <p className="text-sm text-slate-400 mt-0.5 font-mono">{ticket.ticketId}</p>
        </div>
        <div className="ml-auto">
          <StatusBadge
            label={ticket.status}
            variant={getApprovalBadgeVariant(ticket.status)}
          />
        </div>
      </div>

      {actionResult && (
        <div
          className={cn(
            'rounded-xl p-4 flex items-start space-x-3',
            actionResult.type === 'success'
              ? 'bg-emerald-500/5 border border-emerald-500/20'
              : 'bg-red-500/5 border border-red-500/20',
          )}
        >
          {actionResult.type === 'success' ? (
            <CheckCircle2 className="h-4 w-4 text-emerald-400 mt-0.5 shrink-0" />
          ) : (
            <AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
          )}
          <div>
            <div className={cn(
              'text-sm font-medium',
              actionResult.type === 'success' ? 'text-emerald-300' : 'text-red-300',
            )}>
              {actionResult.type === 'success' ? 'Success' : 'Action Failed'}
            </div>
            <div className="text-xs text-slate-400 mt-1">{actionResult.message}</div>
          </div>
        </div>
      )}

      <div className="glass-panel rounded-xl overflow-hidden">
        <div className="px-5 py-4 border-b border-cyan-400/10 flex items-center space-x-3">
          <ShieldCheck className="h-5 w-5 text-cyan-300" />
          <h3 className="text-sm font-semibold text-white">Ticket Details</h3>
        </div>

        <div className="p-5 space-y-4">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <div>
              <span className="text-xs text-slate-400">Action Type</span>
              <div className="text-sm text-white font-medium mt-1">{ticket.actionType}</div>
            </div>
            <div>
              <span className="text-xs text-slate-400">Target Resource</span>
              <div className="text-sm text-white font-medium mt-1">{ticket.targetResource}</div>
            </div>
            <div>
              <span className="text-xs text-slate-400">Created</span>
              <div className="text-sm text-white mt-1">{formatRelativeTime(ticket.createdAt)}</div>
            </div>
            <div>
              <span className="text-xs text-slate-400">Expires</span>
              <div className="text-sm text-white mt-1">{formatRelativeTime(ticket.expiresAt)}</div>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4">
            <div>
              <span className="text-xs text-slate-400">Organization ID</span>
              <div className="text-xs text-slate-300 font-mono mt-1 truncate">{ticket.organizationId}</div>
            </div>
            <div>
              <span className="text-xs text-slate-400">Agent ID</span>
              <div className="text-xs text-slate-300 font-mono mt-1 truncate">{ticket.agentId}</div>
            </div>
          </div>

          {ticket.executionResult && (
            <div>
              <span className="text-xs text-slate-400">Execution Result</span>
              <div className="text-sm text-slate-200 mt-1 font-mono bg-slate-950/60 border border-slate-800 rounded-lg p-3 whitespace-pre-wrap">
                {ticket.executionResult}
              </div>
            </div>
          )}

          {ticket.executionError && (
            <div>
              <span className="text-xs text-slate-400">Execution Error</span>
              <div className="text-sm text-red-300 mt-1 font-mono bg-red-500/5 border border-red-500/20 rounded-lg p-3 whitespace-pre-wrap">
                {ticket.executionError}
              </div>
            </div>
          )}

          {ticket.resolvedAt && (
            <div className="grid grid-cols-2 gap-4">
              <div>
                <span className="text-xs text-slate-400">Resolved At</span>
                <div className="text-sm text-white mt-1">{formatRelativeTime(ticket.resolvedAt)}</div>
              </div>
              {ticket.approverUserId && (
                <div>
                  <span className="text-xs text-slate-400">Approver</span>
                  <div className="text-sm text-white mt-1">{ticket.approverUserId}</div>
                </div>
              )}
            </div>
          )}

          {ticket.status === 'PENDING' && (
            <div className="border-t border-cyan-400/10 pt-4 space-y-3">
              <div>
                <label className="block text-xs text-slate-400 mb-1.5">Notes (optional)</label>
                <input
                  type="text"
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                  placeholder="Add notes about your decision..."
                  className="w-full px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70 transition-colors"
                />
              </div>
              <div className="flex items-center space-x-3">
                <button
                  onClick={() => setConfirmAction('approve')}
                  disabled={actionLoading}
                  className="flex items-center space-x-2 px-4 py-2 text-sm font-medium bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg transition-colors"
                >
                  <CheckCircle2 className="h-4 w-4" />
                  <span>Approve</span>
                </button>
                <button
                  onClick={() => setConfirmAction('reject')}
                  disabled={actionLoading}
                  className="flex items-center space-x-2 px-4 py-2 text-sm font-medium bg-red-600 hover:bg-red-500 text-white rounded-lg transition-colors"
                >
                  <XCircle className="h-4 w-4" />
                  <span>Reject</span>
                </button>
              </div>
            </div>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={confirmAction !== null}
        title={confirmAction === 'approve' ? 'Approve Action' : 'Reject Action'}
        message={
          confirmAction === 'approve'
            ? `You are about to approve action "${ticket.actionType}" on resource "${ticket.targetResource}". This will allow the agent action to proceed.`
            : `You are about to reject action "${ticket.actionType}" on resource "${ticket.targetResource}". The agent action will be blocked.`
        }
        confirmLabel={confirmAction === 'approve' ? 'Approve' : 'Reject'}
        variant={confirmAction === 'approve' ? 'info' : 'danger'}
        onConfirm={handleAction}
        onCancel={() => {
          setConfirmAction(null);
          setNotes('');
        }}
      />
    </div>
  );
}

export function ApprovalsPage() {
  const [activeTab, setActiveTab] = useState<Tab>('PENDING');
  const [tickets, setTickets] = useState<PendingApprovalTicket[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedTicketId, setSelectedTicketId] = useState<string | null>(null);
  const [detailTicket, setDetailTicket] = useState<ApprovalTicketResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  const [lookupId, setLookupId] = useState('');
  const [lookupLoading, setLookupLoading] = useState(false);
  const [lookupError, setLookupError] = useState<string | null>(null);
  const activeTabRef = useRef(activeTab);
  activeTabRef.current = activeTab;

  const fetchApprovals = useCallback(async (status: Tab) => {
    setLoading(true);
    setError(null);
    try {
      const data = await listApprovals({ status });
      setTickets(data);
    } catch (err) {
      setError(isApiError(err) ? err.body.message : getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  const fetchApprovalsRef = useRef(fetchApprovals);
  fetchApprovalsRef.current = fetchApprovals;
  const detailTicketRef = useRef(detailTicket);
  detailTicketRef.current = detailTicket;

  const handleSSEEvent = useCallback((event: { type: string; ticketId?: string; data: Record<string, unknown> }) => {
    const tab = activeTabRef.current;

    switch (event.type) {
      case 'APPROVAL_CREATED':
      case 'APPROVAL_UPDATED': {
        if (tab === 'PENDING') {
          fetchApprovalsRef.current('PENDING');
        }
        if (event.type === 'APPROVAL_CREATED') toast('New approval request pending', { icon: '\u23f3' });
        break;
      }
      case 'APPROVAL_APPROVED':
      case 'APPROVAL_REJECTED': {
        const decision = event.data.status as string | undefined;
        if (tab === (decision === 'APPROVED' ? 'APPROVED' : 'REJECTED')) {
          fetchApprovalsRef.current(tab);
        } else if (tab === 'PENDING') {
          fetchApprovalsRef.current('PENDING');
        }
        const dt = detailTicketRef.current;
        if (dt && event.ticketId === dt.ticketId) {
          getApprovalTicket(event.ticketId).then(setDetailTicket).catch(() => {});
        }
        break;
      }
      case 'APPROVAL_EXECUTED': {
        toast.success('Approved action executed successfully');
        const dt2 = detailTicketRef.current;
        if (dt2 && event.ticketId === dt2.ticketId) {
          getApprovalTicket(event.ticketId).then(setDetailTicket).catch(() => {});
        }
        break;
      }
      case 'APPROVAL_CONTINUATION_FAILED': {
        toast.error('Approval continuation failed');
        const dt3 = detailTicketRef.current;
        if (dt3 && event.ticketId === dt3.ticketId) {
          getApprovalTicket(event.ticketId).then(setDetailTicket).catch(() => {});
        }
        break;
      }
    }
  }, []);

  const handleSync = useCallback(() => {
    fetchApprovalsRef.current(activeTabRef.current);
    const dt = detailTicketRef.current;
    if (dt) {
      getApprovalTicket(dt.ticketId).then(setDetailTicket).catch(() => {});
    }
  }, []);

  const { connected } = useApprovalSSE({ onEvent: handleSSEEvent, onSync: handleSync });

  useEffect(() => {
    fetchApprovals(activeTab);
  }, [activeTab, fetchApprovals]);

  const handleTabChange = (tab: Tab) => {
    setActiveTab(tab);
    setSelectedTicketId(null);
    setDetailTicket(null);
  };

  const handleRowClick = async (ticketId: string) => {
    setSelectedTicketId(ticketId);
    setDetailLoading(true);
    setDetailError(null);
    try {
      const data = await getApprovalTicket(ticketId);
      setDetailTicket(data);
    } catch (err) {
      setDetailError(isApiError(err) ? err.body.message : getErrorMessage(err));
    } finally {
      setDetailLoading(false);
    }
  };

  const handleBack = () => {
    setSelectedTicketId(null);
    setDetailTicket(null);
    setDetailError(null);
  };

  const handleLookup = async () => {
    if (!lookupId.trim()) return;
    setLookupLoading(true);
    setLookupError(null);
    try {
      const data = await getApprovalTicket(lookupId.trim());
      setDetailTicket(data);
      setSelectedTicketId(data.ticketId);
    } catch (err) {
      setLookupError(isApiError(err) ? err.body.message : getErrorMessage(err));
    } finally {
      setLookupLoading(false);
    }
  };

  if (selectedTicketId) {
    if (detailLoading) {
      return (
        <div className="space-y-6">
          <div className="flex items-center space-x-3">
            <button onClick={handleBack} className="text-slate-400 hover:text-white transition-colors">
              <ChevronRight className="h-4 w-4 rotate-180" />
            </button>
            <div>
              <h1 className="text-xl font-bold text-white">Approval Ticket</h1>
              <p className="text-sm text-slate-400 mt-0.5 font-mono">{selectedTicketId}</p>
            </div>
          </div>
          <div className="flex justify-center py-12">
            <LoadingSpinner size="md" />
          </div>
        </div>
      );
    }

    if (detailError) {
      return (
        <div className="space-y-6">
          <div className="flex items-center space-x-3">
            <button onClick={handleBack} className="text-slate-400 hover:text-white transition-colors">
              <ChevronRight className="h-4 w-4 rotate-180" />
            </button>
            <div>
              <h1 className="text-xl font-bold text-white">Approval Ticket</h1>
              <p className="text-sm text-slate-400 mt-0.5 font-mono">{selectedTicketId}</p>
            </div>
          </div>
          <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3">
            <AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
            <div className="text-sm text-red-300">{detailError}</div>
          </div>
        </div>
      );
    }

    if (detailTicket) {
      return <ApprovalDetail ticket={detailTicket} onBack={handleBack} onRefresh={() => fetchApprovals(activeTab)} />;
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="Approval Queue"
        description="Review and manage human approval requests for sensitive agent actions"
      />

      {/* SSE Connection Status */}
      <div className="flex items-center space-x-2 text-xs">
        {connected ? (
          <>
            <Wifi className="h-3 w-3 text-emerald-400" />
            <span className="text-emerald-400">Live updates connected</span>
          </>
        ) : (
          <>
            <WifiOff className="h-3 w-3 text-slate-500" />
            <span className="text-slate-500">Reconnecting...</span>
          </>
        )}
      </div>

      {error && (
        <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3">
          <AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
          <div className="text-sm text-red-300">{error}</div>
          <button onClick={() => fetchApprovals(activeTab)} className="ml-auto text-xs text-slate-400 hover:text-white">
            <RefreshCw className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {/* Tabs */}
      <div className="flex items-center space-x-1 glass-panel rounded-xl p-1">
        {TABS.map((tab) => (
          <button
            key={tab.key}
            onClick={() => handleTabChange(tab.key)}
            className={cn(
              'flex-1 px-4 py-2 text-sm font-medium rounded-lg transition-colors',
              activeTab === tab.key
                ? 'btn-luminous text-white'
                : 'text-slate-400 hover:text-white hover:bg-slate-800/50',
            )}
          >
            {tab.label}
          </button>
        ))}
      </div>

      {/* List */}
      {loading ? (
        <div className="flex justify-center py-12">
          <LoadingSpinner size="md" />
        </div>
      ) : tickets.length === 0 ? (
        <EmptyState
          icon={<Clock className="h-10 w-10" />}
          title={`No ${activeTab.toLowerCase()} approvals`}
          description={`There are no approval tickets with ${activeTab.toLowerCase()} status.`}
        />
      ) : (
        <div className="glass-panel rounded-xl overflow-hidden">
          {/* Header */}
          <div className="px-5 py-3 border-b border-cyan-400/10 grid grid-cols-12 gap-4 text-xs font-medium text-slate-400">
            <div className="col-span-3">Action Type</div>
            <div className="col-span-3">Resource</div>
            <div className="col-span-2">Agent</div>
            <div className="col-span-2">Created</div>
            <div className="col-span-1">Expires</div>
            <div className="col-span-1 text-right">Status</div>
          </div>

          {/* Rows */}
          <div className="divide-y divide-slate-800/50">
            {tickets.map((ticket) => (
              <button
                key={ticket.ticketId}
                onClick={() => handleRowClick(ticket.ticketId)}
                className="w-full px-5 py-3.5 grid grid-cols-12 gap-4 items-center hover:bg-slate-800/30 transition-colors text-left"
              >
                <div className="col-span-3">
                  <div className="text-sm text-white font-medium truncate">{ticket.actionType}</div>
                </div>
                <div className="col-span-3">
                  <div className="text-sm text-slate-300 truncate">{ticket.targetResource}</div>
                </div>
                <div className="col-span-2">
                  <div className="text-xs text-slate-400 font-mono truncate">{truncate(ticket.agentId, 12)}</div>
                </div>
                <div className="col-span-2">
                  <div className="text-xs text-slate-400">{formatRelativeTime(ticket.createdAt)}</div>
                </div>
                <div className="col-span-1">
                  <div className="text-xs text-slate-400">{formatRelativeTime(ticket.expiresAt)}</div>
                </div>
                <div className="col-span-1 flex justify-end">
                  <StatusBadge
                    label={ticket.status}
                    variant={getApprovalBadgeVariant(ticket.status)}
                  />
                </div>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Lookup section */}
      <div className="glass-panel rounded-xl p-5">
        <div className="flex items-center space-x-2 mb-4">
          <Search className="h-4 w-4 text-cyan-300" />
          <h3 className="text-sm font-semibold text-white">Look Up Approval Ticket</h3>
        </div>
        <div className="flex items-center space-x-3">
          <input
            type="text"
            value={lookupId}
            onChange={(e) => setLookupId(e.target.value)}
            placeholder="Enter ticket ID (UUID)"
            className="flex-1 px-3 py-2 bg-slate-950/60 border border-slate-700 rounded-lg text-sm text-white font-mono placeholder-slate-500 focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70 transition-colors"
            onKeyDown={(e) => e.key === 'Enter' && handleLookup()}
          />
          <button
            onClick={handleLookup}
            disabled={!lookupId.trim() || lookupLoading}
            className={cn(
              'flex items-center space-x-2 px-4 py-2 text-sm font-medium rounded-lg transition-colors',
              lookupId.trim() && !lookupLoading
                ? 'btn-luminous text-white'
                : 'bg-slate-800 text-slate-500 cursor-not-allowed',
            )}
          >
            {lookupLoading ? <LoadingSpinner size="sm" className="text-white" /> : <Search className="h-4 w-4" />}
            <span>Look Up</span>
          </button>
        </div>
        {lookupError && (
          <div className="mt-3 flex items-start space-x-2">
            <AlertTriangle className="h-3.5 w-3.5 text-red-400 mt-0.5 shrink-0" />
            <div className="text-xs text-red-300">{lookupError}</div>
          </div>
        )}
        <p className="text-xs text-slate-500 mt-2">
          Enter the approval ticket ID returned by a HUMAN_APPROVAL decision.
        </p>
      </div>
    </div>
  );
}
