import type { Plan, UsageEntry } from '@/api/types';

/** Shown on a repository restored from a previous account, in the list and in the workspace. */
export const RESTORED_NOTE =
  'Restored from your previous account. Analyse it to use the credit you already spent, or delete it to get the credit back.';

/** Ask AI needs a graph and an index, which a not-analysed repository does not have. */
export const ASK_AI_DISABLED_NOTE = 'Ask AI is off until this repository has been analysed.';

/**
 * What deleting a not-analysed entry does to the allowance, from the real usage numbers (never a literal limit). A free user
 * gets exactly one analysis back; a Premium user is only counted, so the number stays. Before the usage has loaded it says it
 * in general terms.
 */
export function creditReturnNote(plan: Plan | undefined, entry: Pick<UsageEntry, 'used' | 'limit'> | undefined): string {
  if (plan === 'PREMIUM') return 'Premium analyses are not limited, so deleting it does not change your count.';
  if (plan === 'FREE' && entry && entry.limit !== null) {
    return `Deleting it gives you back one analysis: you have used ${entry.used} of ${entry.limit}, and will have used ${Math.max(entry.used - 1, 0)} of ${entry.limit}.`;
  }
  return 'On the free plan, deleting it gives you back one free analysis.';
}
