import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useAdminStats } from '@/hooks/useAdmin';
import { formatNumber } from '@/lib/format';
import { NotFoundPage } from '@/features/system/NotFoundPage';
import { AdminHeader } from './AdminNav';
import { useAdminDenied } from './useAdminDenied';

const STATS = [
  { key: 'totalUsers', label: 'Total users', note: 'every account, enabled or not' },
  { key: 'activeUsers', label: 'Active users', note: 'enabled and signed in during the last 30 days' },
  { key: 'premiumUsers', label: 'Premium users', note: 'Premium plans that have not expired' },
  { key: 'totalAnalyses', label: 'Total analyses', note: 'every analysis of every user, any status' },
] as const;

export default function AdminStatsPage() {
  useDocumentTitle('Admin stats');
  const stats = useAdminStats();
  const denied = useAdminDenied(stats.error);
  if (denied) return <NotFoundPage />;
  return (
    <div className="mx-auto grid w-full max-w-[72rem] gap-xl px-md py-lg sm:px-lg lg:py-xl">
      <AdminHeader title="Admin" intro="Account and usage figures, counted live from the database." />
      {stats.isPending ? (
        <div className="stats" role="status" aria-label="Loading stats">
          {STATS.map((s) => <div key={s.key} className="stat"><Skeleton className="h-20 w-full" /></div>)}
        </div>
      ) : stats.isError ? (
        <ErrorState error={stats.error} onRetry={() => void stats.refetch()} />
      ) : (
        <dl className="stats">
          {STATS.map((s) => (
            <div key={s.key} className="stat">
              <dt className="text-sm text-muted">{s.label}</dt>
              <dd className="num text-3xl text-ink">{formatNumber(stats.data[s.key])}</dd>
              <dd className="text-xs text-muted">{s.note}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  );
}
