import { describe, expect, it } from 'vitest';
import type { NodeType } from '@/api/types';
import { inScope, parseScope, SCOPE_DEFAULTS, scopeCounts, scopeParam } from './scope';

describe('parseScope and scopeParam', () => {
  it('uses the tab default when the value is missing or unknown', () => {
    expect(parseScope(null, SCOPE_DEFAULTS.architecture)).toBe('working');
    expect(parseScope(null, SCOPE_DEFAULTS.files)).toBe('all');
    expect(parseScope('bogus', SCOPE_DEFAULTS.relationships)).toBe('working');
    expect(parseScope('', SCOPE_DEFAULTS.files)).toBe('all');
    expect(parseScope('WORKING', 'all')).toBe('all'); // values are exact, lower case
  });

  it('reads the three values as written, whatever the default', () => {
    for (const fallback of ['working', 'all'] as const) {
      expect(parseScope('working', fallback)).toBe('working');
      expect(parseScope('tests', fallback)).toBe('tests');
      expect(parseScope('all', fallback)).toBe('all');
    }
  });

  it('each tab has its own default: working, working and all', () => {
    expect(SCOPE_DEFAULTS).toEqual({ architecture: 'working', relationships: 'working', files: 'all' });
  });

  it('leaves the parameter out at the default and writes a non-default value explicitly', () => {
    expect(scopeParam('working', 'working')).toBeNull();
    expect(scopeParam('all', 'all')).toBeNull();
    expect(scopeParam('all', 'working')).toBe('all');
    expect(scopeParam('tests', 'working')).toBe('tests');
    expect(scopeParam('working', 'all')).toBe('working');
  });
});

describe('inScope', () => {
  it('working keeps non-tests, tests keeps tests, all keeps both', () => {
    expect(inScope({ test: false }, 'working')).toBe(true);
    expect(inScope({ test: true }, 'working')).toBe(false);
    expect(inScope({ test: true }, 'tests')).toBe(true);
    expect(inScope({ test: false }, 'tests')).toBe(false);
    expect(inScope({ test: true }, 'all')).toBe(true);
    expect(inScope({ test: false }, 'all')).toBe(true);
  });
});

describe('scopeCounts', () => {
  const nodes = [
    { id: 'class:A', type: 'CLASS' as const, test: false },
    { id: 'class:B', type: 'CLASS' as const, test: false },
    { id: 'class:ATest', type: 'CLASS' as const, test: true },
    { id: 'method:A#m()', type: 'METHOD' as const, test: false },
    { id: 'method:ATest#t()', type: 'METHOD' as const, test: true },
    { id: 'external:class:Lib', type: 'CLASS' as const, test: false },
  ];

  it('counts what each option would reveal under the node-type filter, without external nodes', () => {
    expect(scopeCounts(nodes, new Set<NodeType>(['CLASS']))).toEqual({ working: 2, tests: 1, all: 3 });
    expect(scopeCounts(nodes, new Set<NodeType>(['CLASS', 'METHOD']))).toEqual({ working: 3, tests: 2, all: 5 });
  });

  it('counts every type when no type filter is given, and nothing for an empty filter', () => {
    expect(scopeCounts(nodes, null)).toEqual({ working: 3, tests: 2, all: 5 });
    expect(scopeCounts(nodes, new Set<NodeType>())).toEqual({ working: 0, tests: 0, all: 0 });
  });
});
