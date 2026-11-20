import { TECHNOLOGY_CATEGORIES, type TechnologyCategory } from '@/api/types';

/** The heading of each group of detected technologies. */
export const TECHNOLOGY_LABEL: Record<TechnologyCategory, string> = {
  BACKEND_FRAMEWORK: 'Backend frameworks',
  FRONTEND_FRAMEWORK: 'Frontend frameworks',
  MOBILE: 'Mobile',
  DESKTOP: 'Desktop',
  AI_ML: 'AI and ML',
  DATA: 'Data',
  BLOCKCHAIN: 'Blockchain',
  DATABASE: 'Databases',
  INFRASTRUCTURE: 'Infrastructure',
  CI_CD: 'CI/CD',
  BUILD_TOOL: 'Build tools',
  TESTING: 'Testing',
  LIBRARY: 'Libraries',
};

/** Display order: what identifies a project first (its frameworks), the supporting tools after. */
export const TECHNOLOGY_ORDER: readonly TechnologyCategory[] = [
  'BACKEND_FRAMEWORK',
  'FRONTEND_FRAMEWORK',
  'MOBILE',
  'DESKTOP',
  'AI_ML',
  'DATA',
  'BLOCKCHAIN',
  'DATABASE',
  'INFRASTRUCTURE',
  'CI_CD',
  'BUILD_TOOL',
  'TESTING',
  'LIBRARY',
];

/** A group shows this many names before "Show N more". */
export const GROUP_VISIBLE_LIMIT = 12;

export interface TechnologyGroup {
  category: TechnologyCategory;
  label: string;
  /** Sorted, without repeats. */
  names: string[];
}

/** A category this build does not know (a newer backend) is a library, so the page never breaks. */
export function technologyCategory(raw: string): TechnologyCategory {
  return (TECHNOLOGY_CATEGORIES as readonly string[]).includes(raw) ? (raw as TechnologyCategory) : 'LIBRARY';
}

function byName(a: string, b: string): number {
  return a.localeCompare(b, 'en', { sensitivity: 'base' }) || (a < b ? -1 : a > b ? 1 : 0);
}

/** The non-empty groups in display order, each with its names sorted. The same input always gives the same output. */
export function groupTechnologies(list: readonly { name: string; category: string }[]): TechnologyGroup[] {
  const byCategory = new Map<TechnologyCategory, Set<string>>();
  for (const { name, category } of list) {
    const key = technologyCategory(category);
    const names = byCategory.get(key) ?? new Set<string>();
    names.add(name);
    byCategory.set(key, names);
  }
  return TECHNOLOGY_ORDER.flatMap((category) => {
    const names = byCategory.get(category);
    return names && names.size > 0 ? [{ category, label: TECHNOLOGY_LABEL[category], names: [...names].sort(byName) }] : [];
  });
}

/** How many technologies the groups hold (what the summary tile counts). */
export function countTechnologies(groups: readonly TechnologyGroup[]): number {
  return groups.reduce((sum, group) => sum + group.names.length, 0);
}

/** The names to draw now and how many are left behind the "Show N more" button. */
export function visibleNames(names: readonly string[], expanded: boolean, limit: number = GROUP_VISIBLE_LIMIT): { shown: string[]; hidden: number } {
  if (expanded || names.length <= limit) return { shown: [...names], hidden: 0 };
  return { shown: names.slice(0, limit), hidden: names.length - limit };
}
