import { describe, expect, it } from 'vitest';
import { TECHNOLOGY_CATEGORIES } from '@/api/types';
import {
  countTechnologies,
  GROUP_VISIBLE_LIMIT,
  groupTechnologies,
  TECHNOLOGY_LABEL,
  TECHNOLOGY_ORDER,
  technologyCategory,
  visibleNames,
} from './technologies';

describe('technology categories', () => {
  it('has a label and a place in the order for every category, and nothing else', () => {
    expect(Object.keys(TECHNOLOGY_LABEL).sort()).toEqual([...TECHNOLOGY_CATEGORIES].sort());
    expect([...TECHNOLOGY_ORDER].sort()).toEqual([...TECHNOLOGY_CATEGORIES].sort());
    expect(new Set(TECHNOLOGY_ORDER).size).toBe(TECHNOLOGY_CATEGORIES.length);
  });

  it('matches the backend list of thirteen', () => {
    expect(TECHNOLOGY_CATEGORIES).toHaveLength(13);
  });

  it('puts the identifying groups first and libraries last', () => {
    expect(TECHNOLOGY_ORDER.slice(0, 2)).toEqual(['BACKEND_FRAMEWORK', 'FRONTEND_FRAMEWORK']);
    expect(TECHNOLOGY_ORDER[TECHNOLOGY_ORDER.length - 1]).toBe('LIBRARY');
  });

  it('reads an unknown category as a library', () => {
    expect(technologyCategory('SERVERLESS')).toBe('LIBRARY');
    expect(technologyCategory('')).toBe('LIBRARY');
    expect(technologyCategory('backend_framework')).toBe('LIBRARY'); // exact names only
    expect(technologyCategory('DATABASE')).toBe('DATABASE');
  });
});

describe('groupTechnologies', () => {
  it('returns nothing for nothing', () => {
    expect(groupTechnologies([])).toEqual([]);
  });

  it('orders the groups by display order, not by the order they arrive in, and drops the empty ones', () => {
    const groups = groupTechnologies([
      { name: 'JUnit', category: 'TESTING' },
      { name: 'Maven', category: 'BUILD_TOOL' },
      { name: 'Spring Boot', category: 'BACKEND_FRAMEWORK' },
      { name: 'PostgreSQL', category: 'DATABASE' },
    ]);
    expect(groups.map((g) => g.category)).toEqual(['BACKEND_FRAMEWORK', 'DATABASE', 'BUILD_TOOL', 'TESTING']);
    expect(groups.map((g) => g.label)).toEqual(['Backend frameworks', 'Databases', 'Build tools', 'Testing']);
  });

  it('sorts the names of a group', () => {
    const [group] = groupTechnologies([
      { name: 'zod', category: 'LIBRARY' },
      { name: 'Axios', category: 'LIBRARY' },
      { name: 'lodash', category: 'LIBRARY' },
    ]);
    expect(group?.names).toEqual(['Axios', 'lodash', 'zod']);
  });

  it('puts an unknown category with the libraries', () => {
    const groups = groupTechnologies([
      { name: 'Redux', category: 'LIBRARY' },
      { name: 'Lambda', category: 'SOMETHING_NEW' },
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0]).toMatchObject({ category: 'LIBRARY', label: 'Libraries', names: ['Lambda', 'Redux'] });
  });

  it('shows a name once per group', () => {
    const [group] = groupTechnologies([
      { name: 'React', category: 'FRONTEND_FRAMEWORK' },
      { name: 'React', category: 'FRONTEND_FRAMEWORK' },
    ]);
    expect(group?.names).toEqual(['React']);
  });

  it('gives the same output for the same input, whatever the input order', () => {
    const input = [
      { name: 'Vite', category: 'BUILD_TOOL' },
      { name: 'React', category: 'FRONTEND_FRAMEWORK' },
      { name: 'Vitest', category: 'TESTING' },
      { name: 'Docker', category: 'INFRASTRUCTURE' },
    ];
    const once = groupTechnologies(input);
    expect(groupTechnologies(input)).toEqual(once);
    expect(groupTechnologies([...input].reverse())).toEqual(once);
  });

  it('does not change what it was given', () => {
    const input = [
      { name: 'b', category: 'LIBRARY' },
      { name: 'a', category: 'LIBRARY' },
    ];
    groupTechnologies(input);
    expect(input.map((t) => t.name)).toEqual(['b', 'a']);
  });
});

describe('countTechnologies', () => {
  it('counts the names in every group', () => {
    const groups = groupTechnologies([
      { name: 'A', category: 'LIBRARY' },
      { name: 'B', category: 'LIBRARY' },
      { name: 'C', category: 'TESTING' },
    ]);
    expect(countTechnologies(groups)).toBe(3);
    expect(countTechnologies([])).toBe(0);
  });
});

describe('visibleNames', () => {
  const names = (n: number) => Array.from({ length: n }, (_, i) => `t${i + 1}`);

  it('shows everything up to the limit', () => {
    expect(visibleNames(names(GROUP_VISIBLE_LIMIT), false)).toEqual({ shown: names(GROUP_VISIBLE_LIMIT), hidden: 0 });
    expect(visibleNames([], false)).toEqual({ shown: [], hidden: 0 });
  });

  it('keeps the first 12 and counts the rest behind the button', () => {
    const result = visibleNames(names(15), false);
    expect(result.shown).toEqual(names(12));
    expect(result.hidden).toBe(3);
  });

  it('shows all of them once expanded', () => {
    expect(visibleNames(names(15), true)).toEqual({ shown: names(15), hidden: 0 });
  });

  it('is exactly one past the limit that starts hiding', () => {
    expect(visibleNames(names(13), false).hidden).toBe(1);
  });
});
