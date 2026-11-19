import { describe, expect, it } from 'vitest';
import type { GraphResponse } from '@/api/types';
import { classicDefault, defaultFilter, displayName, neighbourhood, nodeSize, visibleGraph } from './adapter';

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

  it('falls back to classes and interfaces joined by DEPENDS_ON and inheritance when the modules do not qualify', () => {
    // `graph` has one module and no module edge
    expect(defaultFilter(graph, 'working')).toEqual({ nodeTypes: ['CLASS', 'INTERFACE'], edgeTypes: ['DEPENDS_ON', 'EXTENDS', 'IMPLEMENTS'], moduleDefault: false });
  });

  it('falls back to files and functions for a language without classes or modules that connect', () => {
    const scripts: GraphResponse = { nodes: [{ id: 'function:a.py#f()', type: 'FUNCTION', name: 'f', file: 'a.py', test: false }], edges: [] };
    expect(defaultFilter(scripts, 'working')).toEqual({ nodeTypes: ['FILE', 'FUNCTION'], edgeTypes: ['IMPORTS', 'CALLS', 'CONTAINS'], moduleDefault: false });
    expect(classicDefault(scripts).nodeTypes).toEqual(['FILE', 'FUNCTION']);
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

describe('defaultFilter: the module default', () => {
  const node = (id: string, type: GraphResponse['nodes'][number]['type'], test = false): GraphResponse['nodes'][number] => ({ id, type, name: id.split(':')[1] as string, test });
  const dep = (source: string, target: string): GraphResponse['edges'][number] => ({ source, target, type: 'DEPENDS_ON' });
  const withClasses: GraphResponse = {
    nodes: [node('module:web', 'MODULE'), node('module:service', 'MODULE'), node('module:data', 'MODULE'), node('class:web.Controller', 'CLASS'), node('class:service.Service', 'CLASS')],
    edges: [dep('module:web', 'module:service'), dep('module:service', 'module:data'), dep('class:web.Controller', 'class:service.Service')],
  };

  it('is modules joined by DEPENDS_ON only (no inheritance edges) for a normal graph', () => {
    expect(defaultFilter(withClasses, 'working')).toEqual({ nodeTypes: ['MODULE'], edgeTypes: ['DEPENDS_ON'], moduleDefault: true });
  });

  it('draws exactly the modules and the DEPENDS_ON edges between them', () => {
    const view = defaultFilter(withClasses, 'working');
    const v = visibleGraph(withClasses, { scope: 'working', nodeTypes: new Set(view.nodeTypes), edgeTypes: new Set(view.edgeTypes) });
    expect(v.nodes.every((n) => n.type === 'MODULE')).toBe(true);
    expect(v.edges.map((e) => e.type)).toEqual(['DEPENDS_ON', 'DEPENDS_ON']);
  });

  it('falls back to the classes for a repository with a single module', () => {
    const one: GraphResponse = { nodes: [node('module:app', 'MODULE'), node('class:app.A', 'CLASS'), node('class:app.B', 'CLASS')], edges: [dep('class:app.A', 'class:app.B')] };
    expect(defaultFilter(one, 'working')).toMatchObject({ nodeTypes: ['CLASS', 'INTERFACE'], moduleDefault: false });
  });

  it('falls back when the modules have no DEPENDS_ON edge between them', () => {
    const lonely: GraphResponse = { nodes: [node('module:a', 'MODULE'), node('module:b', 'MODULE'), node('class:a.A', 'CLASS')], edges: [{ source: 'module:a', target: 'class:a.A', type: 'CONTAINS' }] };
    expect(defaultFilter(lonely, 'working')).toMatchObject({ nodeTypes: ['CLASS', 'INTERFACE'], moduleDefault: false });
  });

  it('falls back when the only modules that connect are test code and the scope is working', () => {
    const tests: GraphResponse = {
      nodes: [node('module:app', 'MODULE'), node('module:tests', 'MODULE', true), node('module:tests.util', 'MODULE', true), node('class:app.A', 'CLASS')],
      edges: [dep('module:tests', 'module:tests.util'), dep('module:tests', 'module:app')],
    };
    expect(defaultFilter(tests, 'working')).toMatchObject({ nodeTypes: ['CLASS', 'INTERFACE'], moduleDefault: false });
    expect(defaultFilter(tests, 'all')).toMatchObject({ nodeTypes: ['MODULE'], moduleDefault: true }); // the same graph, judged without the scope
  });

  it('works for a language without classes whose modules connect, and falls back to files and functions when they do not', () => {
    const goLike: GraphResponse = { nodes: [node('module:cmd', 'MODULE'), node('module:internal', 'MODULE'), node('function:cmd/main.go#main()', 'FUNCTION')], edges: [dep('module:cmd', 'module:internal')] };
    expect(defaultFilter(goLike, 'working')).toMatchObject({ nodeTypes: ['MODULE'], moduleDefault: true });
    const disconnected: GraphResponse = { nodes: [node('module:cmd', 'MODULE'), node('module:internal', 'MODULE'), node('function:cmd/main.go#main()', 'FUNCTION')], edges: [] };
    expect(defaultFilter(disconnected, 'working')).toEqual({ nodeTypes: ['FILE', 'FUNCTION'], edgeTypes: ['IMPORTS', 'CALLS', 'CONTAINS'], moduleDefault: false });
  });

  it('ignores external modules and an edge that reaches one', () => {
    const external: GraphResponse = { nodes: [node('module:app', 'MODULE'), node('external:module:lib', 'MODULE'), node('class:app.A', 'CLASS')], edges: [dep('module:app', 'external:module:lib')] };
    expect(defaultFilter(external, 'working').moduleDefault).toBe(false);
  });
});
