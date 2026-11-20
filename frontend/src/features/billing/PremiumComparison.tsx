import { Check, Minus } from 'lucide-react';
import type { BillingPlans } from '@/api/types';
import { PLAN_ROWS } from '@/lib/plans';
import { formatNumber } from '@/lib/format';

/** Included and not included are a check and a dash with words for screen readers: never colour alone. */
function Mark({ included, label }: { included: boolean; label: string }) {
  return (
    <span className="inline-flex items-center gap-2xs">
      {included ? <Check size={16} aria-hidden="true" /> : <Minus size={16} aria-hidden="true" className="text-muted" />}
      <span>{label}</span>
    </span>
  );
}

/**
 * Free against Premium with the server's numbers (`GET /billing/plans`) for the allowances, and `PLAN_ROWS` for the graph
 * features, which keeps their "Coming soon" honesty: a feature the backend does not serve yet is never shown as included today.
 */
export function PremiumComparison({ plans }: { plans: BillingPlans }) {
  const analyses = plans.free.repositoryAnalyses;
  const questions = plans.free.aiQuestionsPerWeek;
  const graphRows = PLAN_ROWS.filter((row) => row.feature !== 'Repository analysis' && row.feature !== 'Ask AI');
  return (
    <div className="overflow-x-auto">
      <table className="table">
        <caption className="sr-only">Free and Premium compared</caption>
        <thead>
          <tr>
            <th scope="col">Feature</th>
            <th scope="col">Free</th>
            <th scope="col">Premium</th>
          </tr>
        </thead>
        <tbody>
          <tr>
            <th scope="row" className="font-normal text-ink">Repository analyses</th>
            <td>{analyses === null ? 'Unlimited' : `${formatNumber(analyses)} in total`}</td>
            <td><Mark included label="Unlimited" /></td>
          </tr>
          <tr>
            <th scope="row" className="font-normal text-ink">Ask AI questions</th>
            <td>{questions === null ? 'Unlimited' : `${formatNumber(questions)} per week`}</td>
            <td>
              <Mark included label="Unlimited" />
              <span className="block text-xs text-muted">Fair use: up to {formatNumber(plans.aiQuestionsPerMinute)} a minute</span>
            </td>
          </tr>
          {graphRows.map((row) => (
            <tr key={row.feature}>
              <th scope="row" className="font-normal text-ink">{row.feature}</th>
              <td><Mark included={row.free !== 'no'} label={row.free === 'no' ? 'Not included' : row.free === 'limited' ? 'Limited' : 'Included'} /></td>
              <td>
                {row.now ? <Mark included={row.premium !== 'no'} label="Included" /> : <span className="text-muted">Coming soon</span>}
              </td>
            </tr>
          ))}
          <tr>
            <th scope="row" className="font-normal text-ink">If Premium ends</th>
            <td colSpan={2} className="text-ink-2">
              You move to Free. After a {plans.graceDays}-day grace period only your {plans.keepRepositories} most recently used repositories are kept; the
              others are deleted.
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  );
}
