import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ArrowLeft, UserCheck, UserX } from 'lucide-react';
import { hasCode } from '@/api/errors';
import { Button } from '@/components/ui/Button';
import { PlanBadge } from '@/components/ui/Chips';
import { Dialog } from '@/components/ui/Dialog';
import { Notice } from '@/components/ui/Notice';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useSession } from '@/hooks/sessionContext';
import { useAdminUser, useSetUserEnabled } from '@/hooks/useAdmin';
import { describeError } from '@/lib/errorCopy';
import { formatDateTime } from '@/lib/format';
import { NotFoundPage } from '@/features/system/NotFoundPage';
import { AdminHeader } from './AdminNav';
import { AccountState } from './AdminUsersPage';
import { useAdminDenied } from './useAdminDenied';

const PROVIDER = { GOOGLE: 'Google', GITHUB: 'GitHub' } as const;

export default function AdminUserPage() {
  useDocumentTitle('Admin user');
  const { userId = '' } = useParams();
  const { me } = useSession();
  const user = useAdminUser(userId);
  const setEnabled = useSetUserEnabled(userId);
  const [confirm, setConfirm] = useState(false);
  const denied = useAdminDenied(user.error ?? setEnabled.error);
  if (denied) return <NotFoundPage />;

  const actionError = setEnabled.error ? describeError(setEnabled.error) : null;

  return (
    <div className="mx-auto grid w-full max-w-[56rem] gap-lg px-md py-lg sm:px-lg lg:py-xl">
      <AdminHeader title="Admin" />
      <Link to="/admin/users" className="link inline-flex items-center gap-2xs text-sm">
        <ArrowLeft size={14} aria-hidden="true" /> All users
      </Link>
      {user.isPending ? (
        <SkeletonRows rows={6} label="Loading the user" />
      ) : user.isError ? (
        hasCode(user.error, 'USER_NOT_FOUND', 'VALIDATION_ERROR') ? (
          <EmptyState title="User not found">This account does not exist, or the link is wrong.</EmptyState>
        ) : (
          <ErrorState error={user.error} onRetry={() => void user.refetch()} />
        )
      ) : (
        <>
          <div className="grid gap-2xs">
            <h2 className="text-xl">{user.data.name}</h2>
            <AccountState user={user.data} />
          </div>
          <dl className="grid gap-x-lg gap-y-md text-sm sm:grid-cols-2">
            {[
              ['Email', user.data.email],
              ['Role', user.data.role === 'ADMIN' ? 'Admin' : 'User'],
              ['Joined', formatDateTime(user.data.createdAt)],
              ['Last sign-in', user.data.lastLoginAt ? formatDateTime(user.data.lastLoginAt) : 'Never'],
              ['Email verified', user.data.emailVerified ? 'Yes' : 'No'],
              ['Locked after failed sign-ins', user.data.locked ? 'Yes' : 'No'],
              ['Linked sign-in', user.data.linkedProviders.length ? user.data.linkedProviders.map((p) => PROVIDER[p]).join(', ') : 'None'],
              ['Plan expires', user.data.planExpiresAt ? formatDateTime(user.data.planExpiresAt) : 'No expiry'],
              ['Plan', <PlanBadge key="plan" plan={user.data.plan} />],
            ].map(([k, v]) => (
              <div key={k as string} className="grid gap-3xs border-b border-rule pb-xs">
                <dt className="text-muted">{k}</dt>
                <dd className="break-words text-ink">{v}</dd>
              </div>
            ))}
          </dl>
          <section className="panel grid gap-sm p-lg" aria-labelledby="status-heading">
            <h3 id="status-heading" className="text-lg">Account status</h3>
            <p className="text-sm text-muted">
              {user.data.enabled ? 'A disabled account cannot sign in, and every session it has ends at once.' : 'Enabling lets this account sign in again.'}
            </p>
            {actionError ? <Notice tone="error" title={actionError.title}>{actionError.message}</Notice> : null}
            <div>
              {user.data.enabled ? (
                <Button variant="danger" icon={<UserX size={16} aria-hidden="true" />} onClick={() => setConfirm(true)} disabled={user.data.id === me?.id} aria-describedby={user.data.id === me?.id ? 'self-note' : undefined}>
                  Disable account
                </Button>
              ) : (
                <Button variant="primary" icon={<UserCheck size={16} aria-hidden="true" />} loading={setEnabled.isPending} onClick={() => setEnabled.mutate(true)}>
                  Enable account
                </Button>
              )}
              {user.data.id === me?.id ? <p id="self-note" className="mt-xs text-xs text-muted">You cannot disable your own account.</p> : null}
            </div>
          </section>
          <Dialog
            open={confirm}
            onClose={() => setConfirm(false)}
            title={`Disable ${user.data.name}?`}
            footer={
              <>
                <Button variant="quiet" onClick={() => setConfirm(false)}>Cancel</Button>
                <Button variant="danger" loading={setEnabled.isPending} onClick={() => setEnabled.mutate(false, { onSettled: () => setConfirm(false) })}>
                  Disable and end sessions
                </Button>
              </>
            }
          >
            <p>This account will not be able to sign in, and all of its sessions end now. You can enable it again later.</p>
          </Dialog>
        </>
      )}
    </div>
  );
}
