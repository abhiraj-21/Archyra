import { Link, useSearchParams } from 'react-router';
import { Pagination } from '@/components/ui/Pagination';
import { PlanBadge } from '@/components/ui/Chips';
import { SkeletonRows } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { ADMIN_PAGE_SIZE, useAdminUsers } from '@/hooks/useAdmin';
import type { AdminUser } from '@/api/types';
import { formatDate } from '@/lib/format';
import { NotFoundPage } from '@/features/system/NotFoundPage';
import { AdminHeader } from './AdminNav';
import { useAdminDenied } from './useAdminDenied';

export function AccountState({ user }: { user: Pick<AdminUser, 'enabled' | 'locked' | 'emailVerified'> }) {
  return (
    <span className="flex flex-wrap gap-2xs">
      <span className="chip" data-tone={user.enabled ? 'success' : 'error'}>{user.enabled ? 'Enabled' : 'Disabled'}</span>
      {user.locked ? <span className="chip" data-tone="warn">Locked</span> : null}
      {user.emailVerified ? null : <span className="chip" data-tone="quiet">Unverified</span>}
    </span>
  );
}

export default function AdminUsersPage() {
  useDocumentTitle('Admin users');
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number(params.get('page')) || 0);
  const users = useAdminUsers(page);
  const denied = useAdminDenied(users.error);
  if (denied) return <NotFoundPage />;

  return (
    <div className="mx-auto grid w-full max-w-[80rem] gap-lg px-md py-lg sm:px-lg lg:py-xl">
      <AdminHeader title="Admin" intro="Every account, newest first." />
      {users.isPending ? (
        <SkeletonRows rows={8} label="Loading users" />
      ) : users.isError ? (
        <ErrorState error={users.error} onRetry={() => void users.refetch()} />
      ) : users.data.totalElements === 0 ? (
        <EmptyState title="No users yet">Accounts appear here once people register.</EmptyState>
      ) : (
        <>
          <div className="overflow-x-auto">
            <table className="table min-w-[56rem]">
              <caption className="sr-only">Users, newest first</caption>
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Email</th>
                  <th scope="col">Role</th>
                  <th scope="col">Plan</th>
                  <th scope="col">Account</th>
                  <th scope="col">Joined</th>
                  <th scope="col">Last sign-in</th>
                </tr>
              </thead>
              <tbody>
                {users.data.items.map((u) => (
                  <tr key={u.id}>
                    <td><Link className="link font-medium" to={`/admin/users/${encodeURIComponent(u.id)}`}>{u.name}</Link></td>
                    <td className="max-w-[16rem] truncate text-ink-2">{u.email}</td>
                    <td className="text-ink-2">{u.role === 'ADMIN' ? 'Admin' : 'User'}</td>
                    <td><PlanBadge plan={u.plan} /></td>
                    <td><AccountState user={u} /></td>
                    <td className="whitespace-nowrap text-muted"><time dateTime={u.createdAt}>{formatDate(u.createdAt)}</time></td>
                    <td className="whitespace-nowrap text-muted">{u.lastLoginAt ? <time dateTime={u.lastLoginAt}>{formatDate(u.lastLoginAt)}</time> : 'Never'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={users.data.page} size={ADMIN_PAGE_SIZE} totalElements={users.data.totalElements} totalPages={users.data.totalPages} onPage={(p) => setParams(p ? { page: String(p) } : {})} label="User pages" />
        </>
      )}
    </div>
  );
}
