import { useState } from 'react';
import { Sparkles } from 'lucide-react';
import { ButtonLink, Button } from '@/components/ui/Button';
import { PlanBadge } from '@/components/ui/Chips';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { useBillingRetention, useBillingStatus, usePayments } from '@/hooks/useBilling';
import { amountDisplay, endSentence, exactDateTime, shortDateTime } from '@/lib/billing';
import { humanize } from '@/lib/format';
import { CancelAutoRenewDialog } from './CancelAutoRenewDialog';
import { RetentionList } from './RetentionList';

const STATUS_WORD: Record<string, string> = { CREATED: 'Not completed', PAID: 'Paid', FAILED: 'Failed', REFUNDED: 'Refunded' };

function PaymentHistory() {
  const payments = usePayments(0, 20);
  if (payments.isPending) return <Skeleton className="h-16 w-full" />;
  if (payments.isError) return <ErrorState error={payments.error} compact onRetry={() => void payments.refetch()} />;
  const rows = payments.data.items.filter((item) => item.status !== 'CREATED');
  if (rows.length === 0) return <p className="text-sm text-muted">No payments yet.</p>;
  return (
    <div className="overflow-x-auto">
      <table className="table">
        <caption className="sr-only">Payment history, newest first</caption>
        <thead>
          <tr>
            <th scope="col">Date</th>
            <th scope="col">Period</th>
            <th scope="col">Amount</th>
            <th scope="col">Status</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((item) => (
            <tr key={item.id}>
              <td>{shortDateTime(item.paidAt ?? item.createdAt)}</td>
              <td>
                {item.period === 'YEARLY' ? 'Annual' : 'Monthly'}
                {item.mode === 'SUBSCRIPTION' ? <span className="text-muted"> (auto-renew)</span> : null}
              </td>
              <td className="num">{amountDisplay(item.amountPaise, item.currency)}</td>
              <td>{STATUS_WORD[item.status] ?? humanize(item.status)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/**
 * Settings → Subscription: the plan, the exact end (always, for Premium: "Ends on Friday, 27 November 2026 at 11:58 (IST)",
 * "Ended on ...", or "No end date"), auto-renew and its next charge, Renew / Manage, the payment history and what would be kept.
 */
export function SubscriptionSection() {
  const status = useBillingStatus();
  const retention = useBillingRetention();
  const [cancelOpen, setCancelOpen] = useState(false);
  if (status.isPending) return <Skeleton className="h-24 w-full" />;
  if (status.isError) return <ErrorState error={status.error} compact onRetry={() => void status.refetch()} />;
  const s = status.data;
  const premiumStored = s.plan === 'PREMIUM';
  const renewing = s.autoRenew.active && !s.autoRenew.cancelAtCycleEnd;

  return (
    <div className="grid gap-lg">
      <div className="grid gap-xs">
        <div className="flex flex-wrap items-center gap-sm">
          <PlanBadge plan={s.effectivePlan} />
          {premiumStored ? (
            <span className="text-ink-2" data-testid="plan-end">
              {endSentence(s.planExpiresAt)}
            </span>
          ) : (
            <span className="text-ink-2">{s.state === 'LAPSED' ? 'Your Premium has ended.' : 'No subscription.'}</span>
          )}
        </div>
        {premiumStored && s.planExpiresAt ? (
          <p className="text-sm text-muted">
            {renewing
              ? `Auto-renew is on${s.autoRenew.nextChargeAt ? `; the next charge is on ${exactDateTime(s.autoRenew.nextChargeAt)}` : ''}.`
              : s.autoRenew.active
                ? 'Auto-renew is cancelled; you will not be charged again.'
                : 'Auto-renew is off. Premium does not renew by itself.'}
          </p>
        ) : null}
        <div className="flex flex-wrap gap-xs">
          {renewing ? (
            <Button onClick={() => setCancelOpen(true)}>Cancel auto-renew</Button>
          ) : (
            <ButtonLink to="/upgrade" variant={s.effectivePlan === 'PREMIUM' ? 'secondary' : 'primary'} icon={<Sparkles size={16} aria-hidden="true" />}>
              {s.effectivePlan === 'PREMIUM' ? 'Renew or add a period' : 'See Premium'}
            </ButtonLink>
          )}
        </div>
      </div>

      <div className="grid gap-xs">
        <h3 className="text-base font-medium text-ink">Payments</h3>
        <PaymentHistory />
      </div>

      {retention.data && premiumStored ? (
        <div className="grid gap-xs">
          <h3 className="text-base font-medium text-ink">Repositories if Premium ends</h3>
          <p className="max-w-[60ch] text-sm text-muted">
            When Premium ends, the Free plan keeps your {retention.data.keep} most recently used repositories after the grace period. Opening a
            repository, asking about it or re-analysing it counts as using it.
          </p>
          <RetentionList kept={retention.data.kept} atRisk={retention.data.atRisk} keep={retention.data.keep} />
        </div>
      ) : null}
      <CancelAutoRenewDialog open={cancelOpen} onClose={() => setCancelOpen(false)} endsAt={s.planExpiresAt} />
    </div>
  );
}
