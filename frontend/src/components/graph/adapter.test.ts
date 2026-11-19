import { describe, expect, it } from 'vitest';
import type { GraphResponse } from '@/api/types';
import { classicDefault, defaultFilter, describeEdge, displayName, drawDirection, HANDLES, idsKey, layoutEdges, matchCounts, matchesQuery, neighbourhood, nodeRole, nodeSize, rankOrder, splitMatch, toFlowEdge, visibleGraph } from './adapter';

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

describe('visibleGraph name filter', () => {
  const repo: GraphResponse = {
    nodes: [
      { id: 'module:com.shop.service', type: 'MODULE', name: 'com.shop.service', test: false },
      { id: 'module:com.shop.web', type: 'MODULE', name: 'com.shop.web', test: false },
      { id: 'class:OrderService', type: 'CLASS', name: 'OrderService', file: 'src/main/OrderService.java', test: false },
      { id: 'class:UserServiceImpl', type: 'CLASS', name: 'UserServiceImpl', file: 'src/main/UserServiceImpl.java', test: false },
      { id: 'class:OrderController', type: 'CLASS', name: 'OrderController', file: 'src/main/OrderController.java', test: false },
      { id: 'interface:UserService', type: 'INTERFACE', name: 'UserService', file: 'src/main/UserService.java', test: false },
      { id: 'class:OrderServiceTest', type: 'CLASS', name: 'OrderServiceTest', file: 'src/test/OrderServiceTest.java', test: true },
      { id: 'external:class:Service', type: 'CLASS', name: 'Service', test: false },
    ],
    edges: [
      { source: 'class:OrderController', target: 'class:OrderService', type: 'DEPENDS_ON' },
      { source: 'class:OrderService', target: 'interface:UserService', type: 'DEPENDS_ON' },
      { source: 'class:UserServiceImpl', target: 'interface:UserService', type: 'IMPLEMENTS' },
      { source: 'class:OrderServiceTest', target: 'class:OrderService', type: 'DEPENDS_ON' },
      { source: 'module:com.shop.web', target: 'module:com.shop.service', type: 'DEPENDS_ON' },
    ],
  };
  const classes = { nodeTypes: all(['CLASS', 'INTERFACE']), edgeTypes: all(['DEPENDS_ON', 'IMPLEMENTS']) };

  it('with only a query keeps the nodes whose name contains it, of the selected types and nothing else', () => {
    const v = visibleGraph(repo, { ...classes, scope: 'all', query: 'service' });
    expect(v.nodes.map((n) => n.id)).toEqual(['class:OrderService', 'class:UserServiceImpl', 'interface:UserService', 'class:OrderServiceTest']);
    expect(v.nodes.some((n) => n.id.startsWith('external:'))).toBe(false);
  });

  it('applies on top of the selected node types: a module that matches is not shown while only classes are selected', () => {
    const v = visibleGraph(repo, { ...classes, scope: 'all', query: 'service' });
    expect(v.nodes.some((n) => n.type === 'MODULE')).toBe(false);
    const interfaces = visibleGraph(repo, { nodeTypes: all(['INTERFACE']), edgeTypes: all(['DEPENDS_ON']), scope: 'all', query: 'service' });
    expect(interfaces.nodes.map((n) => n.id)).toEqual(['interface:UserService']);
  });

  it('applies on top of the scope: working hides the matching test class, tests shows only it, all shows both', () => {
    expect(visibleGraph(repo, { ...classes, scope: 'working', query: 'orderservice' }).nodes.map((n) => n.id)).toEqual(['class:OrderService']);
    expect(visibleGraph(repo, { ...classes, scope: 'tests', query: 'orderservice' }).nodes.map((n) => n.id)).toEqual(['class:OrderServiceTest']);
    expect(visibleGraph(repo, { ...classes, scope: 'all', query: 'orderservice' }).nodes.map((n) => n.id)).toEqual(['class:OrderService', 'class:OrderServiceTest']);
  });

  it('ignores case and surrounding spaces', () => {
    const lower = visibleGraph(repo, { ...classes, scope: 'working', query: 'service' }).nodes.map((n) => n.id);
    expect(visibleGraph(repo, { ...classes, scope: 'working', query: 'SERVICE' }).nodes.map((n) => n.id)).toEqual(lower);
    expect(visibleGraph(repo, { ...classes, scope: 'working', query: '  SeRvIcE  ' }).nodes.map((n) => n.id)).toEqual(lower);
    expect(matchesQuery({ name: 'OrderService' }, 'ORDERSERVICE')).toBe(true);
  });

  it('an empty or blank query is no filter', () => {
    const none = visibleGraph(repo, { ...classes, scope: 'working' });
    expect(visibleGraph(repo, { ...classes, scope: 'working', query: '' }).nodes).toEqual(none.nodes);
    expect(visibleGraph(repo, { ...classes, scope: 'working', query: '   ' }).nodes).toEqual(none.nodes);
  });

  it('drops an edge when either end is filtered out, and never pulls in a neighbour', () => {
    const v = visibleGraph(repo, { ...classes, scope: 'working', query: 'userservice' });
    expect(v.nodes.map((n) => n.id)).toEqual(['class:UserServiceImpl', 'interface:UserService']);
    expect(v.edges.map((e) => e.id)).toEqual(['IMPLEMENTS|class:UserServiceImpl|interface:UserService']);
    const one = visibleGraph(repo, { ...classes, scope: 'working', query: 'orderservice' });
    expect(one.nodes.map((n) => n.id)).toEqual(['class:OrderService']); // its neighbours do not match, so they are not shown
    expect(one.edges).toEqual([]);
    expect(one.degree.get('class:OrderService')).toBe(0);
  });

  it('shows nothing when nothing matches', () => {
    const v = visibleGraph(repo, { ...classes, scope: 'all', query: 'zzz-no-such-name' });
    expect(v.nodes).toEqual([]);
    expect(v.edges).toEqual([]);
  });

  it('matches a module by its full name, not by the label the card shows', () => {
    const modules = { nodeTypes: all(['MODULE']), edgeTypes: all(['DEPENDS_ON']) };
    expect(visibleGraph(repo, { ...modules, scope: 'working', query: 'com.shop' }).nodes).toHaveLength(2);
    expect(visibleGraph(repo, { ...modules, scope: 'working', query: 'shop.web' }).nodes.map((n) => n.id)).toEqual(['module:com.shop.web']);
    expect(visibleGraph(repo, { ...modules, scope: 'working', query: 'web' }).edges).toEqual([]); // the other end does not match
  });

  it('matchCounts is "shown of total" over the selected types and scope', () => {
    expect(matchCounts(repo, { ...classes, scope: 'working', query: 'service' })).toEqual({ shown: 3, total: 4 });
    expect(matchCounts(repo, { ...classes, scope: 'all', query: 'service' })).toEqual({ shown: 4, total: 5 });
    expect(matchCounts(repo, { ...classes, scope: 'working' })).toEqual({ shown: 4, total: 4 });
    expect(matchCounts(repo, { ...classes, scope: 'working', query: 'nope' })).toEqual({ shown: 0, total: 4 });
  });
});

describe('visibleGraph dependents and dependencies of a name match (two independent switches)', () => {
  // `X -> Y` below is an edge with source X and target Y: "X depends on Y" (the direction `UserController DEPENDS_ON UserService`
  // has in Architecture.md and in the API's { source, target } edges). A *dependent* of a match is the source of an edge into it;
  // a *dependency* of a match is the target of an edge out of it.
  const repo: GraphResponse = {
    nodes: [
      { id: 'module:auth', type: 'MODULE', name: 'com.app.auth', test: false },
      { id: 'module:security', type: 'MODULE', name: 'com.app.security', test: false },
      { id: 'module:web', type: 'MODULE', name: 'com.app.web', test: false },
      { id: 'module:reports', type: 'MODULE', name: 'com.app.reports', test: false },
      { id: 'module:authz', type: 'MODULE', name: 'com.app.authz', test: false },
      { id: 'module:billing', type: 'MODULE', name: 'com.app.billing', test: false },
      { id: 'module:auth.tests', type: 'MODULE', name: 'com.app.auth.tests', test: true },
      { id: 'module:checks', type: 'MODULE', name: 'com.app.checks', test: true },
      { id: 'class:AuthService', type: 'CLASS', name: 'AuthService', file: 'src/AuthService.java', test: false },
      { id: 'class:LoginController', type: 'CLASS', name: 'LoginController', file: 'src/LoginController.java', test: false },
      { id: 'external:module:authlib', type: 'MODULE', name: 'authlib', test: false },
    ],
    edges: [
      { source: 'module:security', target: 'module:auth', type: 'DEPENDS_ON' },
      { source: 'module:web', target: 'module:security', type: 'DEPENDS_ON' }, // two hops from auth
      { source: 'module:reports', target: 'module:auth', type: 'IMPORTS' },
      { source: 'module:authz', target: 'module:auth', type: 'DEPENDS_ON' }, // matches "auth" itself and depends on a match
      { source: 'module:billing', target: 'module:web', type: 'DEPENDS_ON' },
      { source: 'module:auth', target: 'module:billing', type: 'DEPENDS_ON' }, // auth depends on billing
      { source: 'module:checks', target: 'module:auth', type: 'DEPENDS_ON' },
      { source: 'module:checks', target: 'module:auth.tests', type: 'DEPENDS_ON' },
      { source: 'module:security', target: 'external:module:authlib', type: 'DEPENDS_ON' },
      { source: 'class:LoginController', target: 'class:AuthService', type: 'DEPENDS_ON' },
      { source: 'module:web', target: 'class:LoginController', type: 'DEPENDS_ON' },
    ],
  };
  const modules = { nodeTypes: all(['MODULE']), edgeTypes: all(['DEPENDS_ON']) };
  const ids = (v: { nodes: { id: string }[] }) => v.nodes.map((n) => n.id);
  const sorted = (set: ReadonlySet<string>) => [...set].sort();
  const view = (query: string, over: Record<string, unknown> = {}) => visibleGraph(repo, { ...modules, scope: 'working', query, includeDependents: true, includeDependencies: true, ...over } as never);

  it('direction: searching "auth" shows security as a dependent (security -> auth), and searching "security" shows auth as a dependency, never the other way round', () => {
    const byAuth = view('auth');
    expect(byAuth.dependentIds.has('module:security')).toBe(true);
    expect(byAuth.dependencyIds.has('module:security')).toBe(false);
    const bySecurity = view('security');
    expect(sorted(bySecurity.dependentIds)).toEqual(['module:web']); // web -> security
    expect(sorted(bySecurity.dependencyIds)).toEqual(['module:auth']); // security -> auth; authlib is external and never shows
    expect(bySecurity.dependentIds.has('module:auth')).toBe(false);
    expect(ids(bySecurity).sort()).toEqual(['module:auth', 'module:security', 'module:web']);
    expect(ids(bySecurity)).not.toContain('module:billing'); // two hops
  });

  it('marks which visible nodes are dependents and which are dependencies; every other visible node is a match', () => {
    const v = view('auth');
    expect(sorted(v.dependentIds)).toEqual(['module:security']); // reports is IMPORTS, authz matches itself, web is two hops away
    expect(sorted(v.dependencyIds)).toEqual(['module:billing']); // auth -> billing
    expect(ids(v).filter((id) => !v.dependentIds.has(id) && !v.dependencyIds.has(id)).sort()).toEqual(['module:auth', 'module:authz']);
    expect(nodeRole('module:security', v, 'auth')).toBe('dependent');
    expect(nodeRole('module:billing', v, 'auth')).toBe('dependency');
    expect(nodeRole('module:auth', v, 'auth')).toBe('match');
    expect(nodeRole('module:authz', v, 'auth')).toBe('match'); // matches AND depends on a match: it is a match
    expect(nodeRole('module:auth', v, '')).toBeNull();
    expect(nodeRole('module:auth', v, '   ')).toBeNull();
    expect(nodeRole('module:auth', v, undefined)).toBeNull();
  });

  it('a node that is both a dependent and a dependency of the matches is shown once, as a dependent', () => {
    const cycle: GraphResponse = {
      nodes: [
        { id: 'a', type: 'MODULE', name: 'auth', test: false },
        { id: 'b', type: 'MODULE', name: 'bridge', test: false },
      ],
      edges: [
        { source: 'a', target: 'b', type: 'DEPENDS_ON' },
        { source: 'b', target: 'a', type: 'DEPENDS_ON' },
      ],
    };
    const v = visibleGraph(cycle, { ...modules, scope: 'all', query: 'auth', includeDependents: true, includeDependencies: true } as never);
    expect(ids(v)).toEqual(['a', 'b']);
    expect(sorted(v.dependentIds)).toEqual(['b']);
    expect(v.dependencyIds.size).toBe(0);
  });

  it('one hop only, in both directions: neither a dependent of a dependent nor a dependency of a dependency is shown', () => {
    const v = view('auth');
    expect(ids(v)).not.toContain('module:web'); // web -> security -> auth, and auth -> billing -> web
    expect(ids(v)).toContain('module:billing'); // but billing is one hop away: auth -> billing
    expect(ids(view('billing')).sort()).toEqual(['module:auth', 'module:billing', 'module:web']); // auth -> billing, billing -> web
    expect(ids(view('billing'))).not.toContain('module:security'); // web <- security is one hop further
  });

  it('never shows an external node, even as a dependency', () => {
    expect(ids(view('security'))).not.toContain('external:module:authlib');
    expect(ids(view('authlib'))).toEqual([]); // an external node is not even a match
  });

  it('uses only the selected edge types to find dependents and dependencies', () => {
    expect(ids(view('auth'))).not.toContain('module:reports'); // reports -> auth is IMPORTS, which is not selected
    const withImports = view('auth', { edgeTypes: all(['DEPENDS_ON', 'IMPORTS']) });
    expect(withImports.dependentIds.has('module:reports')).toBe(true);
    expect(withImports.dependentIds.has('module:security')).toBe(true);
    const importsOnly = view('auth', { edgeTypes: all(['IMPORTS']) });
    expect(sorted(importsOnly.dependentIds)).toEqual(['module:reports']);
    expect(importsOnly.dependencyIds.size).toBe(0); // auth -> billing is DEPENDS_ON, which is not selected
    const none = view('auth', { edgeTypes: all([]) });
    expect(none.dependentIds.size + none.dependencyIds.size).toBe(0);
    expect(ids(none)).toEqual(['module:auth', 'module:authz']); // matches only, as with the filter alone
  });

  it('respects the selected node types: a dependent or a dependency of a type that is not selected stays hidden', () => {
    expect(ids(view('auth', { nodeTypes: all(['MODULE']) }))).not.toContain('class:LoginController');
    const both = view('auth', { nodeTypes: all(['MODULE', 'CLASS']) });
    expect(both.dependentIds.has('class:LoginController')).toBe(true); // depends on AuthService, a match of a selected type
    // a dependency: web -> LoginController
    expect(sorted(view('web').dependencyIds)).toEqual(['module:security']); // LoginController is a class, and only modules are selected
    expect(sorted(view('web', { nodeTypes: all(['MODULE', 'CLASS']) }).dependencyIds)).toEqual(['class:LoginController', 'module:security']);
    // the matches themselves are filtered by the selected types, as before: no class matches while only modules are selected
    expect(ids(view('authservice', { nodeTypes: all(['MODULE']) }))).toEqual([]);
    // what a match of a selected type relates to is found, a match of an unselected type pulls nothing in
    const classes = view('logincontroller', { nodeTypes: all(['CLASS']) });
    expect(ids(classes)).toEqual(['class:AuthService', 'class:LoginController']);
    expect(sorted(classes.dependencyIds)).toEqual(['class:AuthService']); // LoginController -> AuthService
  });

  it('respects all three scopes', () => {
    // working: tests are out of scope, so the test module checks -> auth is not a dependent
    const working = view('auth');
    expect(ids(working)).not.toContain('module:checks');
    expect(ids(working)).not.toContain('module:auth.tests');
    // all: the test module is in scope, depends on auth and shows as a dependent
    const everything = view('auth', { scope: 'all' });
    expect(everything.dependentIds.has('module:checks')).toBe(true);
    expect(ids(everything)).toContain('module:auth.tests'); // a match in scope
    expect(ids(everything)).toContain('module:security');
    expect(everything.dependencyIds.has('module:billing')).toBe(true);
    // tests is strict: only test matches, and only test dependents of them; security (a working node) stays out
    const tests = view('auth', { scope: 'tests' });
    expect(ids(tests).sort()).toEqual(['module:auth.tests', 'module:checks']);
    expect(sorted(tests.dependentIds)).toEqual(['module:checks']);
    expect(tests.edges.map((e) => e.id)).toEqual(['DEPENDS_ON|module:checks|module:auth.tests']); // checks -> auth crosses the boundary
    // dependencies follow the scope as well: checks -> auth and checks -> auth.tests
    expect(sorted(view('checks', { scope: 'all' }).dependencyIds)).toEqual(['module:auth', 'module:auth.tests']);
    expect(sorted(view('checks', { scope: 'tests' }).dependencyIds)).toEqual(['module:auth.tests']);
    expect(view('checks', { scope: 'working' }).nodes).toEqual([]); // checks is test code, so nothing matches under working
  });

  it('the two switches are independent: dependents only, dependencies only, both, or neither', () => {
    const onlyDependents = view('auth', { includeDependencies: false });
    expect(sorted(onlyDependents.dependentIds)).toEqual(['module:security']);
    expect(onlyDependents.dependencyIds.size).toBe(0);
    expect(ids(onlyDependents)).not.toContain('module:billing'); // auth -> billing is a dependency, which is switched off
    const onlyDependencies = view('auth', { includeDependents: false });
    expect(onlyDependencies.dependentIds.size).toBe(0);
    expect(sorted(onlyDependencies.dependencyIds)).toEqual(['module:billing']);
    expect(ids(onlyDependencies)).not.toContain('module:security'); // security -> auth is a dependent, which is switched off
    const both = view('auth');
    expect(ids(both).sort()).toEqual([...new Set([...ids(onlyDependents), ...ids(onlyDependencies)])].sort());
    const neither = view('auth', { includeDependents: false, includeDependencies: false });
    expect(ids(neither)).toEqual(['module:auth', 'module:authz']);
    expect(neither.dependentIds.size + neither.dependencyIds.size).toBe(0);
  });

  it('a node that is both a dependent and a dependency is a dependency when only dependencies are switched on', () => {
    const cycle: GraphResponse = {
      nodes: [
        { id: 'a', type: 'MODULE', name: 'auth', test: false },
        { id: 'b', type: 'MODULE', name: 'bridge', test: false },
      ],
      edges: [
        { source: 'a', target: 'b', type: 'DEPENDS_ON' },
        { source: 'b', target: 'a', type: 'DEPENDS_ON' },
      ],
    };
    const only = (flags: Record<string, boolean>) => visibleGraph(cycle, { ...modules, scope: 'all', query: 'auth', ...flags } as never);
    expect(sorted(only({ includeDependencies: true }).dependencyIds)).toEqual(['b']);
    expect(sorted(only({ includeDependents: true }).dependentIds)).toEqual(['b']);
    expect(sorted(only({ includeDependents: true, includeDependencies: true }).dependentIds)).toEqual(['b']);
    expect(only({ includeDependents: true, includeDependencies: true }).dependencyIds.size).toBe(0);
  });

  it('turning it off is exactly the old behaviour (matches only)', () => {
    const query = 'auth';
    const old = visibleGraph(repo, { ...modules, scope: 'working', query });
    for (const includeDependents of [false, undefined]) {
      const v = visibleGraph(repo, { ...modules, scope: 'working', query, includeDependents, includeDependencies: includeDependents });
      expect(v.nodes).toEqual(old.nodes);
      expect(v.edges).toEqual(old.edges);
      expect([...v.degree]).toEqual([...old.degree]);
      expect(v.dependentIds.size + v.dependencyIds.size).toBe(0);
    }
    expect(ids(old)).toEqual(['module:auth', 'module:authz']);
  });

  it('an empty or blank query has no dependents or dependencies, whatever the toggle says', () => {
    const none = visibleGraph(repo, { ...modules, scope: 'working' });
    for (const query of ['', '   ', undefined]) {
      const v = visibleGraph(repo, { ...modules, scope: 'working', query, includeDependents: true, includeDependencies: true });
      expect(v.nodes).toEqual(none.nodes);
      expect(v.dependentIds.size + v.dependencyIds.size).toBe(0);
    }
  });

  it('draws an edge only when both ends are visible, so the edges between the matches, dependents and dependencies are drawn', () => {
    const v = view('auth');
    const visible = new Set(ids(v));
    for (const e of v.edges) {
      expect(visible.has(e.source)).toBe(true);
      expect(visible.has(e.target)).toBe(true);
    }
    expect(v.edges.map((e) => e.id).sort()).toEqual(['DEPENDS_ON|module:auth|module:billing', 'DEPENDS_ON|module:authz|module:auth', 'DEPENDS_ON|module:security|module:auth'].sort());
    expect(v.edges.some((e) => e.source === 'module:web' || e.target === 'module:web')).toBe(false); // web is hidden, so its edges are too
    expect(v.degree.get('module:auth')).toBe(3);
    expect(v.degree.get('module:security')).toBe(1);
    expect(v.degree.get('module:billing')).toBe(1);
  });

  it('matches case-insensitively and ignores surrounding spaces, like the filter itself', () => {
    const lower = view('auth');
    for (const query of ['AUTH', '  Auth  ', 'aUtH']) {
      const v = view(query);
      expect(ids(v)).toEqual(ids(lower));
      expect(sorted(v.dependentIds)).toEqual(sorted(lower.dependentIds));
      expect(sorted(v.dependencyIds)).toEqual(sorted(lower.dependencyIds));
    }
    expect(ids(view('SECURITY'))).toContain('module:web');
    expect(ids(view('SECURITY'))).toContain('module:auth');
  });

  it('a match with no relations shows alone, and no match shows nothing', () => {
    expect(ids(view('reports'))).toEqual(['module:reports']); // reports -> auth is IMPORTS, not selected
    const nothing = view('zzz-no-such-name');
    expect(nothing.nodes).toEqual([]);
    expect(nothing.edges).toEqual([]);
  });

  it('the filter does not change matchCounts (the total of the selected types and scope)', () => {
    expect(matchCounts(repo, { ...modules, scope: 'working', query: 'auth' })).toEqual({ shown: 2, total: 6 });
  });
});

describe('splitMatch', () => {
  it('splits a label around the first case-insensitive hit, keeping the label spelling', () => {
    expect(splitMatch('UserService', 'service')).toEqual(['User', 'Service', '']);
    expect(splitMatch('AuthServiceAuth', '  AUTH ')).toEqual(['', 'Auth', 'ServiceAuth']);
    expect(splitMatch('web', 'web')).toEqual(['', 'web', '']);
  });

  it('is null with no query or no hit (a module card shows only its last segment)', () => {
    expect(splitMatch('UserService', '')).toBeNull();
    expect(splitMatch('UserService', undefined)).toBeNull();
    expect(splitMatch('UserService', 'zzz')).toBeNull();
    expect(splitMatch(displayName({ name: 'com.shop.web', type: 'MODULE' }), 'shop')).toBeNull();
  });
});

describe('idsKey', () => {
  const n = (...ids: string[]) => ids.map((id) => ({ id }));

  it('differs for two sets of the same size, which is what a name filter produces', () => {
    expect(idsKey(n('a', 'b'), [])).not.toBe(idsKey(n('a', 'c'), []));
    expect(idsKey(n('ab'), [])).not.toBe(idsKey(n('a', 'b'), []));
  });

  it('is the same for the same ids in any order, and differs for edges', () => {
    expect(idsKey(n('a', 'b', 'c'), n('e1'))).toBe(idsKey(n('c', 'a', 'b'), n('e1')));
    expect(idsKey(n('a'), n('e1'))).not.toBe(idsKey(n('a'), n('e2')));
  });
});

describe('drawDirection and toFlowEdge (DEPENDS_ON is drawn dependency → dependent)', () => {
  // A depends on B: the data edge is source A, target B.
  const dep = { id: 'DEPENDS_ON|A|B', type: 'DEPENDS_ON' as const, source: 'A', target: 'B' };
  const names = new Map([['A', 'UserController'], ['B', 'UserService']]);

  it('draws A depends on B from B to A, so the arrowhead lands on the dependent', () => {
    expect(drawDirection(dep)).toEqual({ source: 'B', target: 'A' });
    const drawn = toFlowEdge(dep, names, { A: { y: 200 }, B: { y: 0 } });
    expect([drawn.source, drawn.target, drawn.data.edgeType]).toEqual(['B', 'A', 'DEPENDS_ON']);
    expect(drawn.id).toBe(dep.id); // the id stays the data edge's id
  });

  it('leaves every other edge type exactly as stored', () => {
    for (const type of ['CALLS', 'IMPORTS', 'EXTENDS', 'IMPLEMENTS', 'REFERENCES', 'CONTAINS'] as const) {
      const e = { id: `${type}|A|B`, type, source: 'A', target: 'B' };
      expect(drawDirection(e)).toEqual({ source: 'A', target: 'B' });
      const drawn = toFlowEdge(e, names, {});
      expect([drawn.source, drawn.target, drawn.data.edgeType]).toEqual(['A', 'B', type]);
    }
  });

  it('never mutates the data edge, and leaves source as the dependent', () => {
    const frozen = Object.freeze({ ...dep });
    toFlowEdge(frozen, names, {});
    layoutEdges([frozen]);
    expect(frozen).toEqual({ id: 'DEPENDS_ON|A|B', type: 'DEPENDS_ON', source: 'A', target: 'B' });
  });

  it('the visible graph still carries the data direction (the dependent is the source)', () => {
    const v = visibleGraph(graph, { scope: 'all', nodeTypes: all(['CLASS', 'INTERFACE']), edgeTypes: all(['DEPENDS_ON']) });
    expect(v.edges.map((e) => [e.source, e.target])).toContainEqual(['class:com.demo.UserController', 'class:com.demo.UserService']);
  });

  it('describes an edge in the data direction, whichever way it is drawn', () => {
    expect(describeEdge(dep, names)).toBe('UserController depends on UserService');
    expect(toFlowEdge(dep, names, {}).ariaLabel).toBe('UserController depends on UserService');
    expect(describeEdge({ type: 'EXTENDS', source: 'A', target: 'B' }, names)).toBe('UserController extends UserService');
  });

  it('anchors an arrow that runs up the screen at the top of its source and the bottom of its target', () => {
    const extendsEdge = { id: 'EXTENDS|child|parent', type: 'EXTENDS' as const, source: 'child', target: 'parent' };
    const up = toFlowEdge(extendsEdge, names, { child: { y: 200 }, parent: { y: 0 } });
    expect([up.sourceHandle, up.targetHandle]).toEqual([HANDLES.outTop, HANDLES.inBottom]);
    const down = toFlowEdge(dep, names, { A: { y: 200 }, B: { y: 0 } }); // drawn B (top) → A (below)
    expect([down.sourceHandle, down.targetHandle]).toEqual([HANDLES.outBottom, HANDLES.inTop]);
  });
});

describe('rankOrder (foundations on top)', () => {
  const at = (type: Parameters<typeof rankOrder>[0]['type']) => rankOrder({ type, source: 'A', target: 'B' });

  it('puts what is depended on, extended, implemented or referenced above the node that uses it', () => {
    for (const type of ['DEPENDS_ON', 'EXTENDS', 'IMPLEMENTS', 'REFERENCES'] as const) expect(at(type)).toEqual({ upper: 'B', lower: 'A' });
  });

  it('ranks CALLS, IMPORTS and CONTAINS as they always did (source above target)', () => {
    for (const type of ['CALLS', 'IMPORTS', 'CONTAINS'] as const) expect(at(type)).toEqual({ upper: 'A', lower: 'B' });
  });

  it('a DEPENDS_ON pair is drawn top-down: the drawn source is the upper node', () => {
    const e = { type: 'DEPENDS_ON' as const, source: 'A', target: 'B' };
    expect(drawDirection(e).source).toBe(rankOrder(e).upper);
  });
});
