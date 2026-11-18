/**
 * Free vs Premium, from Architecture.md (V3, "Make the difference between free and premium features"). The one
 * source for the upgrade dialog and the landing page. `now` says whether the backend serves the feature today:
 * the comparison never implies that an unbuilt feature works.
 */
export type Availability = 'yes' | 'no' | 'limited' | 'more';

export interface PlanRow {
  feature: string;
  free: Availability;
  premium: Availability;
  /** Built in the backend today (V0.1/V0.5). */
  now: boolean;
}

export const PLAN_ROWS: PlanRow[] = [
  { feature: 'Repository analysis', free: 'yes', premium: 'yes', now: true },
  { feature: 'Basic architecture graph', free: 'yes', premium: 'yes', now: true },
  { feature: 'Actual classes and modules', free: 'yes', premium: 'yes', now: true },
  { feature: 'Basic relationships', free: 'yes', premium: 'yes', now: true },
  { feature: 'Beginner graph', free: 'yes', premium: 'yes', now: true },
  { feature: 'Ask AI', free: 'limited', premium: 'more', now: false },
  { feature: 'Interactive graph', free: 'no', premium: 'yes', now: false },
  { feature: 'Class to method exploration', free: 'no', premium: 'yes', now: false },
  { feature: 'Method-level dependencies', free: 'no', premium: 'yes', now: false },
  { feature: 'Deep relationship exploration', free: 'no', premium: 'yes', now: false },
  { feature: 'Intermediate graph', free: 'limited', premium: 'yes', now: false },
  { feature: 'Expert graph', free: 'no', premium: 'yes', now: false },
  { feature: 'Advanced graph filters', free: 'no', premium: 'yes', now: false },
];

export const AVAILABILITY_LABEL: Record<Availability, string> = {
  yes: 'Included',
  no: 'Not included',
  limited: 'Limited',
  more: 'More',
};
