import { useCallback, useEffect, useState } from 'react';
import { toast } from 'react-hot-toast';
import { AlertTriangle, RefreshCw, ShieldCheck, UserCheck, Users } from 'lucide-react';
import { getAuthorizedPerson, getErrorMessage, listUsers, transferAuthorizedPerson } from '../api/client';
import { PageHeader } from '../components/ui/PageHeader';
import { StatusBadge } from '../components/ui/StatusBadge';
import { EmptyState, LoadingCard } from '../components/ui/EmptyState';
import { ConfirmDialog } from '../components/ui/ConfirmDialog';
import { formatRelativeTime } from '../lib/utils';
import { useAuth } from '../auth/AuthContext';
import type { AuthorizedPerson, User } from '../types';

export function AuthorizedPersonPage() {
  const { auth } = useAuth();
  const orgId = auth?.organizationId;

  const [person, setPerson] = useState<AuthorizedPerson | null>(null);
  const [members, setMembers] = useState<User[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [selectedUserId, setSelectedUserId] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [transferring, setTransferring] = useState(false);

  const fetchAll = useCallback(async () => {
    if (!orgId) return;
    setLoading(true);
    try {
      const view = await getAuthorizedPerson(orgId);
      setPerson(view);
      setForbidden(false);
      setError(null);
      try {
        setMembers(await listUsers());
      } catch {
        setMembers([]);
      }
    } catch (err) {
      const status = (err as { status?: number }).status;
      if (status === 403) {
        setForbidden(true);
        setError(null);
      } else {
        setError(getErrorMessage(err));
      }
    } finally {
      setLoading(false);
    }
  }, [orgId]);

  useEffect(() => { fetchAll(); }, [fetchAll]);

  const isDesignated = !!person?.userId && !!auth && person.userId === auth.userId;
  const designated = person?.userId ? person : null;
  const candidates = members.filter(
    (m) => m.id !== auth?.userId && m.id !== person?.userId && m.status === 'ACTIVE',
  );

  const selectedMember = candidates.find((m) => m.id === selectedUserId);

  const doTransfer = async () => {
    if (!orgId || !person?.userId || !selectedUserId) return;
    setTransferring(true);
    try {
      const view = await transferAuthorizedPerson(orgId, selectedUserId, person.userId);
      setPerson(view);
      setSelectedUserId('');
      setConfirmOpen(false);
      toast.success('Authorized person transferred');
      await fetchAll();
    } catch (err) {
      toast.error(getErrorMessage(err));
      setConfirmOpen(false);
    } finally {
      setTransferring(false);
    }
  };

  if (!auth) return null;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Authorized Person"
        description="The single person who may administer this organization, approve human handoffs, and read its audit trail"
      />

      {error && (
        <div className="bg-red-500/5 border border-red-500/20 rounded-xl p-4 flex items-start space-x-3">
          <AlertTriangle className="h-4 w-4 text-red-400 mt-0.5 shrink-0" />
          <div className="text-sm text-red-300">{error}</div>
          <button onClick={fetchAll} className="ml-auto text-xs text-slate-400 hover:text-white">
            <RefreshCw className="h-3.5 w-3.5" />
          </button>
        </div>
      )}

      {loading ? (
        <div className="space-y-3"><LoadingCard /><LoadingCard /></div>
      ) : forbidden ? (
        <EmptyState
          icon={<ShieldCheck className="h-10 w-10" />}
          title="Only the designated person can view this"
          description="This organization's designation is not visible to ordinary members. Ask the designated person or the deployment owner."
        />
      ) : (
        <>
          <div className="glass-panel rounded-xl p-5">
            <div className="flex items-center justify-between mb-4">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wide">Current designation</span>
              {designated ? (
                <StatusBadge label={designated.active ? 'Designated' : 'Inactive'} variant={designated.active ? 'success' : 'warning'} />
              ) : (
                <StatusBadge label="Fail-closed" variant="danger" />
              )}
            </div>

            {designated ? (
              <div className="flex items-start space-x-4">
                <div className="h-10 w-10 rounded-full bg-violet-500/15 border border-violet-400/30 flex items-center justify-center shrink-0 shadow-glow-violet">
                  <UserCheck className="h-5 w-5 text-violet-300" />
                </div>
                <div className="min-w-0">
                  <div className="text-sm font-semibold text-white truncate">
                    {designated.fullName || designated.email || designated.userId}
                  </div>
                  <div className="text-xs text-slate-400 truncate">{designated.email}</div>
                  <div className="text-xs text-slate-500 mt-1">
                    Designated {designated.designatedAt ? formatRelativeTime(designated.designatedAt) : ''}
                    {isDesignated ? ' — this is you' : ''}
                  </div>
                </div>
              </div>
            ) : (
              <p className="text-sm text-slate-400 leading-relaxed">
                Nobody is designated for this organization. It admits no configuration administration,
                no human approval decisions, and no audit access until the deployment owner designates
                the first person or the current person transfers the designation.
              </p>
            )}
          </div>

          <div className="glass-panel rounded-xl p-5">
            <div className="flex items-center justify-between mb-4">
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wide">Transfer designation</span>
              <Users className="h-4 w-4 text-slate-600" />
            </div>

            {!isDesignated ? (
              <p className="text-sm text-slate-400 leading-relaxed">
                Only the current designated person may transfer this designation. There is no role,
                second administrator, or shared account that can also do so.
              </p>
            ) : candidates.length === 0 ? (
              <p className="text-sm text-slate-400 leading-relaxed">
                No other active members are available in this organization to receive the designation.
              </p>
            ) : (
              <div className="space-y-4">
                <p className="text-sm text-slate-400 leading-relaxed">
                  The new person must already be an active member of this organization. Transferring
                  immediately moves administration and human approvals to them; you retain no
                  administrative authority afterwards.
                </p>
                <select
                  value={selectedUserId}
                  onChange={(e) => setSelectedUserId(e.target.value)}
                  className="w-full px-3 py-2.5 bg-slate-950 border border-slate-800 rounded-xl text-sm text-white focus:outline-none focus:ring-2 focus:ring-cyan-400/40 focus:border-cyan-400/70 transition-colors"
                >
                  <option value="">Select a member…</option>
                  {candidates.map((m) => (
                    <option key={m.id} value={m.id}>
                      {m.fullName || m.email} ({m.email})
                    </option>
                  ))}
                </select>
                <button
                  onClick={() => setConfirmOpen(true)}
                  disabled={!selectedUserId || transferring}
                  className="px-4 py-2.5 text-sm font-medium rounded-lg btn-luminous text-white transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  Transfer designation
                </button>
              </div>
            )}
          </div>
        </>
      )}

      <ConfirmDialog
        open={confirmOpen}
        title="Transfer authorized person?"
        message={
          selectedMember
            ? `${selectedMember.fullName || selectedMember.email} becomes this organization's only authorized person. You will immediately lose the ability to administer configuration, approve actions, and read the audit trail.`
            : 'This member becomes this organization\'s only authorized person.'
        }
        confirmLabel="Transfer"
        variant="warning"
        onConfirm={doTransfer}
        onCancel={() => setConfirmOpen(false)}
      />
    </div>
  );
}
