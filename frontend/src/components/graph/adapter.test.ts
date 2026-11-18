import { describe, expect, it } from 'vitest';
import type { GraphResponse } from '@/api/types';
import { defaultFilter, displayName, neighbourhood, nodeSize, visibleGraph } from './adapter';

// Small inline inputs for the pure adapter only.
const graph: GraphResponse = {
  nodes: [
    { id: 'repository:demo', type: 'REPOSITORY', name: 'demo', test: false },
    { id: 'module:com.demo', type: 'MODULE', name: 'com.demo', test: false },
    { id: 'class:com.demo.UserController', type: 'CLASS', name: 'UserController', file: 'src/UserController.java', test: false },
    { id: 'class:com.demo.UserService', type: 'CLASS', name: 'UserService', file: 'src/UserService.java', test: false },
    { id: 'interface:com.demo.UserRepository', type: 'INTERFACE', name: 'UserRepository', file: 'src/UserRepository.java', test: false },
    { id: 'external:class:java.util.List', type: 'CLASS', name: 'List', test: false },
  ],
  edges: [
    { source: 'class:com.demo.UserController', target: 'class:com.demo.UserService', type: 'DEPENDS_ON' },
    { source: 'class:com.demo.UserService', target: 'interface:com.demo.UserRepository', type: 'DEPENDS_ON' },
    { source: 'class:com.demo.UserService', target: 'external:class:java.util.List', type: 'REFERENCES' },
    { source: 'module:com.demo', target: 'class:com.demo.UserService', type: 'CONTAINS' },
    { source: 'class:com.demo.UserController', target: 'class:com.demo.UserService', type: 'DEPENDS_ON' },
  ],
};

const all = (types: string[]) => new Set(types) as never;

describe('visibleGraph', () => {
  it('drops external nodes and every edge touching them', () => {
    const v = visibleGraph(graph, { scope: 'all', nodeTypes: all(['CLASS', 'INTERFACE']), edgeTypes: all(['DEPENDS_ON', 'REFERENCES']) });
    expect(v.nodes.map((n) => n.id)).not.toContain('external:class:java.util.List');
    expect(v.edges.some((e) => e.target.startsWith('external:'))).toBe(false);
  });

  it('keeps an edge only when both ends are visible', () => {
    const v = visibleGraph(graph, { scope: 'all', nodeTypes: all(['CLASS']), edgeTypes: all(['DEPENDS_ON', 'CONTAINS']) });
    expect(v.edges.map((e) => e.type)).toEqual(['DEPENDS_ON']);
  });

  it('deduplicates identical edges and counts degree', () => {
    const v = visibleGraph(graph, { scope: 'all', nodeTypes: all(['CLASS', 'INTERFACE']), edgeTypes: all(['DEPENDS_ON']) });
    expect(v.edges).toHaveLength(2);
    expect(v.degree.get('class:com.demo.UserService')).toBe(2);
  });

  it('accepts nodes without a file (repository, module)', () => {
    const v = visibleGraph(graph, { scope: 'all', nodeTypes: all(['MODULE', 'REPOSITORY']), edgeTypes: all([]) });
    expect(v.nodes.map((n) => n.file)).toEqual([undefined, undefined]);
  });
});

describe('neighbourhood and defaults', () => {
  it('collects direct connections in both directions', () => {
    const edges = visibleGraph(graph, { scope: 'all', nodeTypes: all(['CLASS', 'INTERFACE']), edgeTypes: all(['DEPENDS_ON']) }).edges;
    expect([...neighbourhood(edges, 'class:com.demo.UserService')].sort()).toEqual([
      'class:com.demo.UserController',
      'class:com.demo.UserService',
      'interface:com.demo.UserRepository',
    ]);
  });

  it('defaults to classes and interfaces joined by DEPENDS_ON', () => {
    expect(defaultFilter(graph)).toMatchObject({ nodeTypes: ['CLASS', 'INTERFACE'], edgeTypes: expect.arrayContaining(['DEPENDS_ON']) });
  });

  it('falls back to modules, then files and functions, when there are no classes', () => {
    const modules: GraphResponse = {
      nodes: [
        { id: 'module:a', type: 'MODULE', name: 'a', test: false },
        { id: 'module:b', type: 'MODULE', name: 'b', test: false },
      ],
      edges: [{ source: 'module:a', target: 'module:b', type: 'DEPENDS_ON' }],
    };
    expect(defaultFilter(modules).nodeTypes).toEqual(['MODULE']);
    const scripts: GraphResponse = { nodes: [{ id: 'function:a.py#f()', type: 'FUNCTION', name: 'f', file: 'a.py', test: false }], edges: [] };
    expect(defaultFilter(scripts).nodeTypes).toEqual(['FILE', 'FUNCTION']);
  });

  it('labels modules by their last package segment', () => {
    expect(displayName({ type: 'MODULE', name: 'org.example.owner' })).toBe('owner');
    expect(displayName({ type: 'MODULE', name: '(default)' })).toBe('(default)');
    expect(displayName({ type: 'CLASS', name: 'Owner' })).toBe('Owner');
  });

  it('sizes cards within bounds', () => {
    expect(nodeSize({ name: 'A' }).width).toBe(132);
    expect(nodeSize({ name: 'X'.repeat(200) }).width).toBe(280);
  });
});

describe('visibleGraph scope', () => {
  // Small inline repository: two working classes and a test class that depends on one of them.
  const mixed: GraphResponse = {
    nodes: [
      { id: 'class:A', type: 'CLASS', name: 'A', file: 'src/main/A.java', test: false },
      { id: 'class:B', type: 'CLASS', name: 'B', file: 'src/main/B.java', test: false },
      { id: 'class:ATest', type: 'CLASS', name: 'ATest', file: 'src/test/ATest.java', test: true },
      { id: 'class:Fixtures', type: 'CLASS', name: 'Fixtures', file: 'src/test/Fixtures.java', test: true },
      { id: 'external:class:Lib', type: 'CLASS', name: 'Lib', test: false },
    ],
    edges: [
      { source: 'class:A', target: 'class:B', type: 'DEPENDS_ON' },
      { source: 'class:ATest', target: 'class:A', type: 'DEPENDS_ON' },
      { source: 'class:ATest', target: 'class:Fixtures', type: 'DEPENDS_ON' },
    ],
  };
  const filter = (scope: 'working' | 'tests' | 'all') => ({ scope, nodeTypes: all(['CLASS']), edgeTypes: all(['DEPENDS_ON']) });

  it('working hides test nodes and the edges that reach them', () => {
    const v = visibleGraph(mixed, filter('working'));
    expect(v.nodes.map((n) => n.id)).toEqual(['class:A', 'class:B']);
    expect(v.edges.map((e) => e.id)).toEqual(['DEPENDS_ON|class:A|class:B']);
  });

  it('tests is strict: only test nodes, and only the edges among them (an edge across the boundary is dropped)', () => {
    const v = visibleGraph(mixed, filter('tests'));
    expect(v.nodes.map((n) => n.id)).toEqual(['class:ATest', 'class:Fixtures']);
    expect(v.edges.map((e) => e.id)).toEqual(['DEPENDS_ON|class:ATest|class:Fixtures']);
    expect(v.edges.some((e) => e.target === 'class:A')).toBe(false);
  });

  it('all keeps every non-external node and edge', () => {
    const v = visibleGraph(mixed, filter('all'));
    expect(v.nodes).toHaveLength(4);
    expect(v.edges).toHaveLength(3);
  });

  it('applies to modules in a repository without classes', () => {
    const modules: GraphResponse = {
      nodes: [
        { id: 'module:app', type: 'MODULE', name: 'app', test: false },
        { id: 'module:tests', type: 'MODULE', name: 'tests', test: true },
      ],
      edges: [{ source: 'module:tests', target: 'module:app', type: 'DEPENDS_ON' }],
    };
    const f = (scope: 'working' | 'tests' | 'all') => ({ scope, nodeTypes: all(['MODULE']), edgeTypes: all(['DEPENDS_ON']) });
    expect(visibleGraph(modules, f('working')).nodes.map((n) => n.id)).toEqual(['module:app']);
    expect(visibleGraph(modules, f('working')).edges).toEqual([]);
    expect(visibleGraph(modules, f('tests')).nodes.map((n) => n.id)).toEqual(['module:tests']);
    expect(visibleGraph(modules, f('all')).edges).toHaveLength(1);
  });

  it('applies to functions in a function-oriented repository', () => {
    const scripts: GraphResponse = {
      nodes: [
        { id: 'function:tools/c.py#clean()', type: 'FUNCTION', name: 'clean', file: 'tools/c.py', test: false },
        { id: 'function:tests/test_c.py#test_clean()', type: 'FUNCTION', name: 'test_clean', file: 'tests/test_c.py', test: true },
      ],
      edges: [{ source: 'function:tests/test_c.py#test_clean()', target: 'function:tools/c.py#clean()', type: 'CALLS' }],
    };
    const f = (scope: 'working' | 'tests' | 'all') => ({ scope, nodeTypes: all(['FUNCTION']), edgeTypes: all(['CALLS']) });
    expect(visibleGraph(scripts, f('working')).nodes.map((n) => n.name)).toEqual(['clean']);
    expect(visibleGraph(scripts, f('tests')).nodes.map((n) => n.name)).toEqual(['test_clean']);
    expect(visibleGraph(scripts, f('tests')).edges).toEqual([]);
    expect(visibleGraph(scripts, f('all')).edges).toHaveLength(1);
  });
});
