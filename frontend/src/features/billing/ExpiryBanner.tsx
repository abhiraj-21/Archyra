import { ButtonLink } from '@/components/ui/Button';
import { Notice } from '@/components/ui/Notice';
import { useBillingPlans, useBillingStatus } from '@/hooks/useBilling';
import { expiryNotice } from '@/lib/billing';
import { useNow } from './useNow';

/**
 * On every page of the app while Premium is ending (EXPIRING) or has ended and the repositories are not deleted yet (GRACE). Not
 * dismissible while the state lasts. Time is worded like the Ask AI reset ("ends in 3 days", "today at 05:30").
 */
export function ExpiryBanner() {
  const status = useBillingStatus();
  const plans = useBillingPlans();
  const now = useNow();
  const notice = expiryNotice(status.data, plans.data?.keepRepositories ?? 5, now);
  if (!notice) return null;
  return (
    <div className="border-b border-rule bg-paper-2 px-md py-sm sm:px-lg" data-testid="expiry-banner">
      <Notice
        tone={notice.tone}
        title={notice.title}
        action={
          <div className="flex flex-wrap gap-xs">
            <ButtonLink to="/upgrade" variant="primary" size="sm">
              Renew
            </ButtonLink>
            {status.data?.state === 'GRACE' && notice.atRisk > 0 ? (
              <ButtonLink to="/settings#subscription" variant="secondary" size="sm">
                See what will be kept
              </ButtonLink>
            ) : null}
          </div>
        }
      >
        {notice.body}
      </Notice>
    </div>
  );
}
