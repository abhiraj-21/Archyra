import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { Check, Minus } from 'lucide-react';
import { Button, ButtonLink } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { useAnalysisUsage } from '@/hooks/useAccount';
import { AVAILABILITY_LABEL, PLAN_ROWS, type Availability } from '@/lib/plans';
import { UpgradeContext } from '@/hooks/upgradeContext';

/** Included and not included are a check and a dash (two shapes, never colour alone); the words stay for screen readers. */
function Cell({ value }: { value: Availability }) {
  const iconOnly = value === 'yes' || value === 'no';
  return (
    <span className="inline-flex items-center gap-2xs">
      {value === 'no' ? <Minus size={16} aria-hidden="true" className="text-muted" /> : <Check size={16} aria-hidden="true" />}
      <span className={iconOnly ? 'sr-only' : ''}>{AVAILABILITY_LABEL[value]}</span>
    </span>
  );
}

export function PlanComparisonTable({ caption }: { caption?: string }) {
  return (
    <table className="table">
      {caption ? <caption className="sr-only">{caption}</caption> : null}
      <thead>
        <tr>
          <th scope="col">Feature</th>
          <th scope="col">Free</th>
          <th scope="col">Premium</th>
          <th scope="col">Today</th>
        </tr>
      </thead>
      <tbody>
        {PLAN_ROWS.map((row) => (
          <tr key={row.feature}>
            <th scope="row" className="font-normal text-ink">
              {row.feature}
            </th>
            <td>
              <Cell value={row.free} />
            </td>
            <td>
              <Cell value={row.premium} />
            </td>
            <td className="text-muted">{row.now ? 'Available' : 'Coming soon'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** One upgrade dialog for the whole app: why Premium, and a link to the payment screen (`/upgrade`), where the checkout is. */
export function UpgradeProvider({ children }: { children: ReactNode }) {
  const [feature, setFeature] = useState<string | null>(null);
  const [open, setOpen] = useState(false);
  const { plan } = useAnalysisUsage();

  const openUpgrade = useCallback((name?: string) => {
    setFeature(name ?? null);
    setOpen(true);
  }, []);
  const value = useMemo(() => ({ openUpgrade }), [openUpgrade]);

  return (
    <UpgradeContext.Provider value={value}>
      {children}
      <Dialog
        open={open}
        onClose={() => setOpen(false)}
        size="wide"
        title={feature ? `${feature} needs Premium` : 'Archyra Premium'}
        footer={
          <>
            <Button variant="quiet" onClick={() => setOpen(false)}>
              Close
            </Button>
            <ButtonLink to="/upgrade" variant="primary" onClick={() => setOpen(false)}>
              {plan === 'PREMIUM' ? 'Manage Premium' : 'See prices and upgrade'}
            </ButtonLink>
          </>
        }
      >
        <div className="grid gap-md">
          <p>
            {plan === 'PREMIUM'
              ? 'You are on Premium. Features marked coming soon are not built yet for any plan.'
              : 'Premium removes the analysis and Ask AI limits. Features marked coming soon are not built yet for any plan.'}
          </p>
          <div className="overflow-x-auto">
            <PlanComparisonTable caption="Free and Premium compared" />
          </div>
        </div>
      </Dialog>
    </UpgradeContext.Provider>
  );
}
