import { describe, expect, it } from 'vitest';
import type { GraphResponse } from '@/api/types';
import { buildExamples, COUNTING_NOTE, FALLBACK_CLASS, FALLBACK_MODULE, largestModule, mostDependedOnClass, WONT_WORK } from './askGuide';

// Small inline inputs for the pure builder only.
const graph: GraphResponse = {
  nodes: [
    { id: 'repository:shop', type: 'REPOSITORY', name: 'shop', test: false },
    { id: 'module:com.shop.web', type: 'MODULE', name: 'com.shop.web', test: false },
    { id: 'module:com.shop.model', type: 'MODULE', name: 'com.shop.model', test: false },
    { id: 'module:com.shop.tests', type: 'MODULE', name: 'com.shop.tests', test: true },
    { id: 'file:web/A.java', type: 'FILE', name: 'A.java', file: 'web/A.java', test: false },
    { id: 'file:model/B.java', type: 'FILE', name: 'B.java', file: 'model/B.java', test: false },
    { id: 'file:model/C.java', type: 'FILE', name: 'C.java', file: 'model/C.java', test: false },
    { id: 'file:tests/T1.java', type: 'FILE', name: 'T1.java', file: 'tests/T1.java', test: true },
    { id: 'file:tests/T2.java', type: 'FILE', name: 'T2.java', file: 'tests/T2.java', test: true },
    { id: 'file:tests/T3.java', type: 'FILE', name: 'T3.java', file: 'tests/T3.java', test: true },
    { id: 'class:Controller', type: 'CLASS', name: 'Controller', file: 'web/A.java', test: false },
    { id: 'class:Order', type: 'CLASS', name: 'Order', file: 'model/B.java', test: false },
    { id: 'interface:Money', type: 'INTERFACE', name: 'Money', file: 'model/C.java', test: false },
    { id: 'class:OrderTest', type: 'CLASS', name: 'OrderTest', file: 'tests/T1.java', test: true },
    { id: 'external:class:List', type: 'CLASS', name: 'List', test: false },
  ],
  edges: [
    { source: 'module:com.shop.web', target: 'file:web/A.java', type: 'CONTAINS' },
    { source: 'module:com.shop.model', target: 'file:model/B.java', type: 'CONTAINS' },
    { source: 'module:com.shop.model', target: 'file:model/C.java', type: 'CONTAINS' },
    { source: 'module:com.shop.tests', target: 'file:tests/T1.java', type: 'CONTAINS' },
    { source: 'module:com.shop.tests', target: 'file:tests/T2.java', type: 'CONTAINS' },
    { source: 'module:com.shop.tests', target: 'file:tests/T3.java', type: 'CONTAINS' },
    { source: 'class:Controller', target: 'class:Order', type: 'DEPENDS_ON' },
    { source: 'interface:Money', target: 'class:Order', type: 'DEPENDS_ON' },
    { source: 'class:Order', target: 'interface:Money', type: 'DEPENDS_ON' },
    { source: 'class:OrderTest', target: 'interface:Money', type: 'DEPENDS_ON' },
    { source: 'class:OrderTest', target: 'interface:Money', type: 'DEPENDS_ON' },
    { source: 'class:Controller', target: 'external:class:List', type: 'DEPENDS_ON' },
    { source: 'class:Controller', target: 'class:Order', type: 'CALLS' },
  ],
};

describe('the examples use the repository’s own names', () => {
  it('picks the most depended-on class, ignoring tests, external nodes and other relationships', () => {
    // Order is depended on by Controller and Money; Money only by Order (the test class's two edges do not count)
    expect(mostDependedOnClass(graph)).toBe('Order');
  });

  it('breaks a tie by name', () => {
    const tied: GraphResponse = {
      nodes: [
        { id: 'class:B', type: 'CLASS', name: 'B', file: 'b', test: false },
        { id: 'class:A', type: 'CLASS', name: 'A', file: 'a', test: false },
        { id: 'class:C', type: 'CLASS', name: 'C', file: 'c', test: false },
      ],
      edges: [
        { source: 'class:C', target: 'class:B', type: 'DEPENDS_ON' },
        { source: 'class:C', target: 'class:A', type: 'DEPENDS_ON' },
      ],
    };
    expect(mostDependedOnClass(tied)).toBe('A');
  });

  it('picks the module with the most non-test files, even when a test module has more', () => {
    expect(largestModule(graph)).toBe('com.shop.model');
  });

  it('has no answer for a graph without dependencies or modules', () => {
    expect(mostDependedOnClass({ nodes: graph.nodes, edges: [] })).toBeNull();
    expect(largestModule({ nodes: graph.nodes.filter((n) => n.type !== 'MODULE'), edges: graph.edges })).toBeNull();
  });

  it('builds the four groups with those names in the questions that need them', () => {
    const examples = buildExamples(graph);
    expect(examples).toMatchObject({ className: 'Order', moduleName: 'com.shop.model', placeholders: false });
    expect(examples.groups.map((g) => g.title)).toEqual(['The project', 'How things work', 'Relationships', 'Explain code']);
    const questions = examples.groups.flatMap((g) => g.examples.map((e) => e.question));
    expect(questions).toContain('What is this project about?');
    expect(questions).toContain('What technologies does it use?');
    expect(questions).toContain('How is it structured?');
    expect(questions).toContain('How does authentication work?');
    expect(questions).toContain('Where does a request to /users go?');
    expect(questions).toContain('What classes depend on `Order`?');
    expect(questions).toContain('What does `Order` call?');
    expect(questions).toContain('Explain `Order`');
    expect(questions).toContain('What is the role of the `com.shop.model` module?');
  });

  it('falls back to static names, and says so, while the graph is unavailable or has none', () => {
    for (const unavailable of [undefined, { nodes: [], edges: [] }]) {
      const examples = buildExamples(unavailable);
      expect(examples).toMatchObject({ className: FALLBACK_CLASS, moduleName: FALLBACK_MODULE, placeholders: true });
      const questions = examples.groups.flatMap((g) => g.examples.map((e) => e.question));
      expect(questions).toContain(`Explain \`${FALLBACK_CLASS}\``);
    }
    // one real name is not enough to call the examples real
    expect(buildExamples({ nodes: graph.nodes.filter((n) => n.type !== 'MODULE'), edges: graph.edges }).placeholders).toBe(true);
  });
});

describe('the guide’s words', () => {
  it('lists every kind of question that will not work', () => {
    const text = WONT_WORK.map((item) => `${item.title} ${item.detail}`).join(' ');
    for (const expected of [
      'General programming help',
      'Fix this bug',
      'refactor X',
      'Test code is not indexed',
      'Runtime behaviour',
      'commit history',
      'issues and pull requests',
      'Swift',
      'Dockerfile',
      'list all classes',
      'Architecture and Files tabs',
      'Each conversation is separate',
    ]) {
      expect(text, expected).toContain(expected);
    }
  });

  it('says honestly what counts, in the spec’s words, and holds no number', () => {
    expect(COUNTING_NOTE).toBe(
      'A question that reaches the AI uses one of your questions, even if the answer is "I can\'t tell". A question with no match in this repository is stopped first and isn\'t counted.',
    );
    expect(COUNTING_NOTE).not.toMatch(/\d/);
  });
});
