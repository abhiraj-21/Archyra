import type { GraphNode, NodeType } from '@/api/types';

/**
 * Which code a view shows: `working` hides test code, `tests` shows only test code (strict), `all` shows everything.
 * Each tab keeps its own scope in its own `?scope=` parameter, left out of the URL when it equals the tab's default.
 */
export const SCOPES = ['working', 'tests', 'all'] as const;
export type Scope = (typeof SCOPES)[number];

export const SCOPE_DEFAULTS = { architecture: 'working', relationships: 'working', files: 'all' } as const satisfies Record<string, Scope>;
export type ScopeTab = keyof typeof SCOPE_DEFAULTS;

/** An unknown or missing value falls back to the tab's default. */
export function parseScope(value: string | null, fallback: Scope): Scope {
  return (SCOPES as readonly string[]).includes(value ?? '') ? (value as Scope) : fallback;
}

/** What to put in the URL: nothing at the default, so default URLs stay clean and a non-default value is explicit. */
export function scopeParam(scope: Scope, fallback: Scope): string | null {
  return scope === fallback ? null : scope;
}

export function inScope(node: Pick<GraphNode, 'test'>, scope: Scope): boolean {
  return scope === 'all' || (scope === 'tests' ? node.test : !node.test);
}

/** How many nodes each option would show under the current node-type filter (`null` types: every type). */
export function scopeCounts(nodes: readonly Pick<GraphNode, 'id' | 'type' | 'test'>[], types: ReadonlySet<NodeType> | null): Record<Scope, number> {
  const counts: Record<Scope, number> = { working: 0, tests: 0, all: 0 };
  for (const n of nodes) {
    if (n.id.startsWith('external:') || (types && !types.has(n.type))) continue;
    counts.all += 1;
    counts[n.test ? 'tests' : 'working'] += 1;
  }
  return counts;
}

export const SCOPE_LABELS: Record<Scope, string> = { working: 'Working', tests: 'Tests', all: 'All' };

export const SCOPE_HELP =
  'Working hides test code, Tests shows only test code, All shows everything. Test code is recognised by path and file name: ' +
  'src/test, test(s) and spec folders, *Test and *Tests, test_*.py, *_test.go, *.test.ts and *.spec.ts. ' +
  'Rust unit tests written inline with #[cfg(test)] are not detected.';
