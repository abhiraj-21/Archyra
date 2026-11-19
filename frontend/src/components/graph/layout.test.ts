import { describe, expect, it } from 'vitest';
import type { EdgeType } from '@/api/types';
import { layoutEdges } from './adapter';
import { computeLayout } from './layoutCore';
import { LAYOUT_VERSION, layoutCacheKey } from './layoutVersion';

const box = (id: string) => ({ id, width: 100, height: 40 });
const edge = (type: EdgeType, source: string, target: string) => ({ id: `${type}|${source}|${target}`, type, source, target });
// What the pages do: data edges (source depends on target) become ranking edges, then the layout runs.
const layout = (ids: string[], edges: ReturnType<typeof edge>[]) => computeLayout({ nodes: ids.map(box), edges: layoutEdges(edges) }).positions;

describe('layout', () => {
  it('places an edge\'s upper end above its lower end and packs isolated nodes below', () => {
    const { positions } = computeLayout({
      nodes: [box('a'), box('b'), box('c'), box('lonely')],
      edges: [{ id: 'e1', source: 'a', target: 'b' }, { id: 'e2', source: 'b', target: 'c' }, { id: 'e3', source: 'a', target: 'c' }],
    });
    expect(positions.a!.y).toBeLessThan(positions.b!.y);
    expect(positions.b!.y).toBeLessThan(positions.c!.y);
    expect(positions.lonely!.y).toBeGreaterThan(positions.c!.y);
  });
});

describe('layout of DEPENDS_ON (foundations on top)', () => {
  it('A depends on B puts B above A', () => {
    const p = layout(['A', 'B'], [edge('DEPENDS_ON', 'A', 'B')]);
    expect(p.B!.y).toBeLessThan(p.A!.y);
  });

  it('a chain controller → service → repository puts repository on top and controller at the bottom', () => {
    const p = layout(['controller', 'service', 'repository'], [edge('DEPENDS_ON', 'controller', 'service'), edge('DEPENDS_ON', 'service', 'repository')]);
    expect(p.repository!.y).toBeLessThan(p.service!.y);
    expect(p.service!.y).toBeLessThan(p.controller!.y);
  });

  it('a dependency cycle lays out without throwing and positions every node', () => {
    const edges = [edge('DEPENDS_ON', 'a', 'b'), edge('DEPENDS_ON', 'b', 'c'), edge('DEPENDS_ON', 'c', 'a'), edge('DEPENDS_ON', 'a', 'b'), edge('DEPENDS_ON', 'b', 'b')];
    let p: ReturnType<typeof layout> = {};
    expect(() => (p = layout(['a', 'b', 'c'], edges))).not.toThrow();
    for (const id of ['a', 'b', 'c']) expect(Number.isFinite(p[id]!.y) && Number.isFinite(p[id]!.x)).toBe(true);
  });

  it('a two-node cycle (a ⇄ b) still lays out', () => {
    expect(() => layout(['a', 'b'], [edge('DEPENDS_ON', 'a', 'b'), edge('DEPENDS_ON', 'b', 'a')])).not.toThrow();
  });
});

describe('layout of a mixed class graph (the ranking rule)', () => {
  it('an interface is above its implementation, a parent above its child, and both agree with DEPENDS_ON', () => {
    // Controller → Service → Repository (interface) ← JpaRepository (implements it); Admin extends Base; Service depends on Base too.
    const ids = ['Controller', 'Service', 'Repository', 'JpaRepository', 'Base', 'Admin'];
    const p = layout(ids, [
      edge('DEPENDS_ON', 'Controller', 'Service'),
      edge('DEPENDS_ON', 'Service', 'Repository'),
      edge('IMPLEMENTS', 'JpaRepository', 'Repository'),
      edge('EXTENDS', 'Admin', 'Base'),
      edge('DEPENDS_ON', 'Service', 'Base'),
    ]);
    expect(p.Repository!.y).toBeLessThan(p.JpaRepository!.y); // interface above its implementation
    expect(p.Base!.y).toBeLessThan(p.Admin!.y); // parent above its child
    expect(p.Repository!.y).toBeLessThan(p.Service!.y); // depended-on above dependent
    expect(p.Service!.y).toBeLessThan(p.Controller!.y);
    expect(p.Base!.y).toBeLessThan(p.Service!.y);
  });

  it('REFERENCES on the same pair as DEPENDS_ON does not contradict it', () => {
    const p = layout(['A', 'B'], [edge('DEPENDS_ON', 'A', 'B'), edge('REFERENCES', 'A', 'B')]);
    expect(p.B!.y).toBeLessThan(p.A!.y);
  });

  it('CALLS, IMPORTS and CONTAINS still put the source above the target', () => {
    for (const type of ['CALLS', 'IMPORTS', 'CONTAINS'] as const) {
      const p = layout(['A', 'B'], [edge(type, 'A', 'B')]);
      expect(p.A!.y).toBeLessThan(p.B!.y);
    }
  });
});

describe('layout versioning', () => {
  it('the cache key changes with the layout version, so an old layout is never reused', () => {
    expect(layoutCacheKey('set', 1)).not.toBe(layoutCacheKey('set', 2));
    expect(layoutCacheKey('set')).toBe(layoutCacheKey('set', LAYOUT_VERSION));
    expect(layoutCacheKey('one')).not.toBe(layoutCacheKey('two'));
  });

  it('is past the version that drew the dependent on top', () => {
    expect(LAYOUT_VERSION).toBeGreaterThan(1);
  });
});
