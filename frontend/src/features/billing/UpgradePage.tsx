import { useEffect, useId, useRef, useState } from 'react';
import { CreditCard, Repeat } from 'lucide-react';
import type { BillingPeriod, BillingPlans, BillingStatus } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Notice } from '@/components/ui/Notice';
import { Skeleton } from '@/components/ui/Skeleton';
import { ErrorState } from '@/components/ui/States';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useBillingPlans, useBillingStatus, useRazorpayCheckout, type CheckoutState } from '@/hooks/useBilling';
import { amountDisplay, annualSaving, exactDateTime, payLabel, priceOf } from '@/lib/billing';
import { describeError } from '@/lib/errorCopy';
import { CancelAutoRenewDialog } from './CancelAutoRenewDialog';
import { PremiumComparison } from './PremiumComparison';
import { Segmented } from './Segmented';

type Mode = 'once' | 'auto';

const MODE_HELP: Record<Mode, string> = {
  once: 'Pay once for one period. Nothing renews by itself; we remind you a few days before it ends.',
  auto: 'Razorpay charges the same amount at the start of every period until you cancel. Cancel any time from here or Settings; Premium then stays until the end of the period you paid for.',
};

/** What the live region says for each step of a payment. */
function phaseMessage(state: CheckoutState): string {
  switch (state.phase) {
    case 'creating':
      return 'Preparing your payment.';
    case 'loading':
      return 'Opening Razorpay.';
    case 'checkout':
      return 'Complete the payment in the Razorpay window.';
    case 'confirming':
      return 'Payment received. Confirming it with Razorpay; this can take up to 30 seconds.';
    case 'done':
      return `Premium is active until ${exactDateTime(state.status?.planExpiresAt)}.`;
    case 'pending':
      return 'Razorpay has not confirmed the payment yet. Premium starts as soon as it does; you do not need to pay again.';
    case 'dismissed':
      return 'Payment window closed. Nothing was charged.';
    default:
      return '';
  }
}

function CurrentPlan({ status, onCancel }: { status: BillingStatus; onCancel: () => void }) {
  if (status.effectivePlan !== 'PREMIUM') return null;
  const renewing = status.autoRenew.active && !status.autoRenew.cancelAtCycleEnd;
  return (
    <section className="panel grid gap-xs p-md" aria-label="Your Premium">
      <h2 className="text-lg">You are on Premium</h2>
      <p className="text-ink-2">
        {status.planExpiresAt ? `Premium is active until ${exactDateTime(status.planExpiresAt)}.` : 'Premium has no end date.'}
      </p>
      {renewing ? (
        <>
          <p className="text-sm text-muted">
            Renews automatically ({status.autoRenew.period === 'YEARLY' ? 'annually' : 'monthly'})
            {status.autoRenew.nextChargeAt ? `; next charge on ${exactDateTime(status.autoRenew.nextChargeAt)}` : ''}.
          </p>
          <div>
            <Button variant="secondary" onClick={onCancel}>
              Cancel auto-renew
            </Button>
          </div>
        </>
      ) : status.autoRenew.active && status.autoRenew.cancelAtCycleEnd ? (
        <p className="text-sm text-muted">Auto-renew is cancelled: you will not be charged again.</p>
      ) : status.planExpiresAt ? (
        <p className="text-sm text-muted">It does not renew by itself. Add a period below to extend it; the days you have left are kept.</p>
      ) : null}
    </section>
  );
}

function PayControls({ plans, status }: { plans: BillingPlans; status: BillingStatus | undefined }) {
  const [period, setPeriod] = useState<BillingPeriod>('MONTHLY');
  const [mode, setMode] = useState<Mode>('once');
  const { state, start, reset } = useRazorpayCheckout();
  const modeHelpId = useId();
  const liveRef = useRef<HTMLParagraphElement>(null);
  const saving = annualSaving(plans);
  const autoAvailable = priceOf(plans, period)?.autoRenewAvailable ?? false;
  const effectiveMode: Mode = autoAvailable ? mode : 'once';
  const busy = ['creating', 'loading', 'checkout', 'confirming'].includes(state.phase);
  const failure = state.phase === 'failed' ? (state.reason ? { title: 'The payment did not go through', message: state.reason } : describeError(state.error)) : null;
  const isPremium = status?.effectivePlan === 'PREMIUM';

  useEffect(() => {
    if (state.phase === 'done') liveRef.current?.focus();
  }, [state.phase]);

  const periodLabel = (value: BillingPeriod) => {
    const price = priceOf(plans, value);
    const amount = price ? amountDisplay(price.amountPaise, plans.currency) : '';
    return value === 'MONTHLY' ? `Monthly · ${amount}` : `Annual · ${amount}`;
  };

  return (
    <section className="grid gap-md" aria-labelledby="pay-heading">
      <h2 id="pay-heading" className="text-lg">
        {isPremium ? 'Add a period' : 'Choose how to pay'}
      </h2>
      <div className="grid gap-2xs">
        <Segmented
          label="Billing period"
          value={period}
          onChange={(next) => {
            setPeriod(next);
            reset();
          }}
          options={[
            { value: 'MONTHLY', label: periodLabel('MONTHLY') },
            { value: 'YEARLY', label: periodLabel('YEARLY') },
          ]}
        />
        {saving ? (
          <p className="text-sm text-muted">
            Annual saves {amountDisplay(saving.paise, plans.currency)} ({saving.percent}%) against twelve monthly payments. Prices include taxes.
          </p>
        ) : (
          <p className="text-sm text-muted">Prices include taxes.</p>
        )}
      </div>
      <div className="grid gap-2xs">
        <Segmented
          label="Payment type"
          value={effectiveMode}
          describedBy={modeHelpId}
          onChange={(next) => {
            setMode(next);
            reset();
          }}
          options={[
            { value: 'once', label: 'One-time payment' },
            { value: 'auto', label: 'Auto-renew', disabled: !autoAvailable },
          ]}
        />
        <p id={modeHelpId} className="max-w-[60ch] text-sm text-muted">
          {MODE_HELP[effectiveMode]}
          {!autoAvailable ? ' Auto-renew is not available for this period right now.' : ''}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-sm">
        <Button
          variant="primary"
          size="lg"
          icon={effectiveMode === 'auto' ? <Repeat size={18} aria-hidden="true" /> : <CreditCard size={18} aria-hidden="true" />}
          loading={busy}
          disabled={busy}
          aria-describedby="pay-status"
          data-testid="pay-button"
          onClick={() => void start(period, effectiveMode, status)}
        >
          {payLabel(plans, period, effectiveMode)}
        </Button>
        <span className="text-xs text-muted">Payments are handled by Razorpay: cards, UPI, netbanking and wallets.</span>
      </div>
      <p id="pay-status" ref={liveRef} tabIndex={-1} className="text-sm text-ink-2 focus:outline-none" role="status" aria-live="polite">
        {phaseMessage(state)}
      </p>
      {failure ? (
        <Notice tone="error" title={failure.title}>
          {failure.message}
        </Notice>
      ) : null}
    </section>
  );
}

/** `/upgrade`: what Premium adds, the price, and Razorpay's checkout. Every number comes from the server. */
export default function UpgradePage() {
  useDocumentTitle('Premium');
  const plans = useBillingPlans();
  const status = useBillingStatus();
  const [cancelOpen, setCancelOpen] = useState(false);

  const renewing = !!status.data && status.data.autoRenew.active && !status.data.autoRenew.cancelAtCycleEnd;

  return (
    <div className="mx-auto grid w-full max-w-[56rem] gap-lg px-md py-lg sm:px-lg lg:py-xl">
      <header className="grid gap-2xs">
        <h1 className="text-2xl">Archyra Premium</h1>
        <p className="max-w-[60ch] text-muted">Unlimited analyses and Ask AI questions, and the graph features as they arrive.</p>
      </header>

      {plans.isPending ? (
        <Skeleton className="h-48 w-full" />
      ) : plans.isError ? (
        <ErrorState error={plans.error} onRetry={() => void plans.refetch()} />
      ) : (
        <>
          {status.data ? <CurrentPlan status={status.data} onCancel={() => setCancelOpen(true)} /> : null}
          {!plans.data.enabled ? (
            <Notice tone="info" title="Payments are not available right now">
              Premium cannot be bought at the moment. Nothing has changed on your account; please check again later.
            </Notice>
          ) : renewing ? (
            <Notice tone="info" title="Premium renews automatically">
              To pay another way, cancel auto-renew first. Premium stays until the end of the period you paid for.
            </Notice>
          ) : (
            <PayControls plans={plans.data} status={status.data} />
          )}
          <section className="grid gap-sm" aria-labelledby="compare-heading">
            <h2 id="compare-heading" className="text-lg">
              Free and Premium
            </h2>
            <PremiumComparison plans={plans.data} />
          </section>
        </>
      )}
      <CancelAutoRenewDialog open={cancelOpen} onClose={() => setCancelOpen(false)} endsAt={status.data?.planExpiresAt ?? null} />
    </div>
  );
}
