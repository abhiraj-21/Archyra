import { describe, expect, it } from 'vitest';
import type { GraphResponse } from '@/api/types';
import { defaultFilter, displayName, neighbourhood, nodeSize, visibleGraph } from './adapter';

// Small inline inputs for the pure adapter only.
const graph: GraphResponse = {
  nodes: [
    { id: 'repository:demo', type: 'REPOSITORY', name: 'demo' },
    { id: 'module:com.demo', type: 'MODULE', name: 'com.demo' },
    { id: 'class:com.demo.UserController', type: 'CLASS', name: 'UserController', file: 'src/UserController.java' },
    { id: 'class:com.demo.UserService', type: 'CLASS', name: 'UserService', file: 'src/UserService.java' },
    { id: 'interface:com.demo.UserRepository', type: 'INTERFACE', name: 'UserRepository', file: 'src/UserRepository.java' },
    { id: 'external:class:java.util.List', type: 'CLASS', name: 'List' },
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
    const v = visibleGraph(graph, { nodeTypes: all(['CLASS', 'INTERFACE']), edgeTypes: all(['DEPENDS_ON', 'REFERENCES']) });
    expect(v.nodes.map((n) => n.id)).not.toContain('external:class:java.util.List');
    expect(v.edges.some((e) => e.target.startsWith('external:'))).toBe(false);
  });

  it('keeps an edge only when both ends are visible', () => {
    const v = visibleGraph(graph, { nodeTypes: all(['CLASS']), edgeTypes: all(['DEPENDS_ON', 'CONTAINS']) });
    expect(v.edges.map((e) => e.type)).toEqual(['DEPENDS_ON']);
  });

  it('deduplicates identical edges and counts degree', () => {
    const v = visibleGraph(graph, { nodeTypes: all(['CLASS', 'INTERFACE']), edgeTypes: all(['DEPENDS_ON']) });
    expect(v.edges).toHaveLength(2);
    expect(v.degree.get('class:com.demo.UserService')).toBe(2);
  });

  it('accepts nodes without a file (repository, module)', () => {
    const v = visibleGraph(graph, { nodeTypes: all(['MODULE', 'REPOSITORY']), edgeTypes: all([]) });
    expect(v.nodes.map((n) => n.file)).toEqual([undefined, undefined]);
  });
});

describe('neighbourhood and defaults', () => {
  it('collects direct connections in both directions', () => {
    const edges = visibleGraph(graph, { nodeTypes: all(['CLASS', 'INTERFACE']), edgeTypes: all(['DEPENDS_ON']) }).edges;
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
        { id: 'module:a', type: 'MODULE', name: 'a' },
        { id: 'module:b', type: 'MODULE', name: 'b' },
      ],
      edges: [{ source: 'module:a', target: 'module:b', type: 'DEPENDS_ON' }],
    };
    expect(defaultFilter(modules).nodeTypes).toEqual(['MODULE']);
    const scripts: GraphResponse = { nodes: [{ id: 'function:a.py#f()', type: 'FUNCTION', name: 'f', file: 'a.py' }], edges: [] };
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
