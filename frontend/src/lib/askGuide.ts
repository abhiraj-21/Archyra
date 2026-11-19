import type { GraphEdge, GraphNode, GraphResponse } from '@/api/types';

/**
 * What the Ask AI tab tells people to ask, and what not to. The examples are built from the repository's own names, read from
 * the graph the Architecture tab already caches, so "What classes depend on `OrderService`?" is a question this repository can
 * answer; a static fallback stands in only while the graph is not there.
 */

/** The names used when the graph is unavailable. They are placeholders, and the guide says so. */
export const FALLBACK_CLASS = 'PaymentService';
export const FALLBACK_MODULE = 'payments';

export interface GuideExample {
  /** The question that fills the composer. It never sends. */
  question: string;
}

export interface GuideGroup {
  title: string;
  examples: GuideExample[];
}

export interface GuideExamples {
  /** The most depended-on class or interface that is not test or external code; the fallback when there is none. */
  className: string;
  /** The module with the most non-test files; the fallback when there is none. */
  moduleName: string;
  /** True when either name is a placeholder rather than something from this repository. */
  placeholders: boolean;
  groups: GuideGroup[];
}

const isCode = (node: GraphNode) => (node.type === 'CLASS' || node.type === 'INTERFACE') && !node.test && !node.id.startsWith('external:');

/** The class or interface that the most other classes and interfaces depend on (DEPENDS_ON in-degree); ties by name. */
export function mostDependedOnClass(graph: GraphResponse): string | null {
  const classes = new Map(graph.nodes.filter(isCode).map((node) => [node.id, node]));
  const dependents = new Map<string, Set<string>>();
  for (const edge of graph.edges) {
    if (edge.type !== 'DEPENDS_ON' || edge.source === edge.target || !classes.has(edge.source) || !classes.has(edge.target)) continue;
    const set = dependents.get(edge.target) ?? new Set<string>();
    set.add(edge.source);
    dependents.set(edge.target, set);
  }
  const best = [...dependents.entries()]
    .map(([id, set]) => ({ node: classes.get(id) as GraphNode, count: set.size }))
    .sort((a, b) => b.count - a.count || a.node.name.localeCompare(b.node.name))[0];
  return best?.node.name ?? null;
}

/** The module that contains the most non-test files (CONTAINS edges from the module to its files); ties by name. */
export function largestModule(graph: GraphResponse): string | null {
  const files = new Map(graph.nodes.filter((n) => n.type === 'FILE' && !n.test && !n.id.startsWith('external:')).map((n) => [n.id, n]));
  const modules = new Map(graph.nodes.filter((n) => n.type === 'MODULE' && !n.test && !n.id.startsWith('external:')).map((n) => [n.id, n]));
  const sizes = new Map<string, number>();
  for (const edge of graph.edges as GraphEdge[]) {
    if (edge.type === 'CONTAINS' && modules.has(edge.source) && files.has(edge.target)) sizes.set(edge.source, (sizes.get(edge.source) ?? 0) + 1);
  }
  const best = [...sizes.entries()]
    .map(([id, count]) => ({ node: modules.get(id) as GraphNode, count }))
    .sort((a, b) => b.count - a.count || a.node.name.localeCompare(b.node.name))[0];
  return best?.node.name ?? null;
}

/** The groups of examples, with `X` and `y` from the graph (or the placeholders when it is missing or has none). */
export function buildExamples(graph: GraphResponse | undefined): GuideExamples {
  const found = graph ? mostDependedOnClass(graph) : null;
  const module = graph ? largestModule(graph) : null;
  const className = found ?? FALLBACK_CLASS;
  const moduleName = module ?? FALLBACK_MODULE;
  return {
    className,
    moduleName,
    placeholders: found === null || module === null,
    groups: [
      {
        title: 'The project',
        examples: [{ question: 'What is this project about?' }, { question: 'What technologies does it use?' }, { question: 'How is it structured?' }],
      },
      {
        title: 'How things work',
        examples: [{ question: 'How does authentication work?' }, { question: 'Where does a request to /users go?' }],
      },
      {
        title: 'Relationships',
        examples: [{ question: `What classes depend on \`${className}\`?` }, { question: `What does \`${className}\` call?` }],
      },
      {
        title: 'Explain code',
        examples: [{ question: `Explain \`${className}\`` }, { question: `What is the role of the \`${moduleName}\` module?` }],
      },
    ],
  };
}

export interface WontWork {
  title: string;
  detail: string;
}

/** Questions that will be refused or that waste one of the user's questions, exactly as the spec words them. */
export const WONT_WORK: readonly WontWork[] = [
  { title: 'Not about this repository', detail: 'General programming help, other projects, "write me a function", anything off-topic.' },
  { title: 'Asking the AI to change code', detail: '"Fix this bug", "refactor X". It explains code; it does not edit it.' },
  { title: 'How code is tested', detail: 'Test code is not indexed, so tests are invisible to the AI.' },
  {
    title: 'Things that are not in the code',
    detail: 'Runtime behaviour, logs, performance, deployment, commit history, who wrote what, issues and pull requests.',
  },
  {
    title: 'Languages Archyra only counts',
    detail: 'Swift, Dart, C, C++, Scala, R, Julia, shell, SQL, HCL, Dockerfile, HTML and CSS are counted but not analysed.',
  },
  { title: '"Explain everything" or "list all classes"', detail: 'Use the Architecture and Files tabs instead.' },
  { title: 'Questions that depend on another conversation', detail: 'Each conversation is separate.' },
];

/** What counts, in the words the spec uses; shown beside the composer and in the guide, next to the real remaining count. */
export const COUNTING_NOTE =
  'A question that reaches the AI uses one of your questions, even if the answer is "I can\'t tell". A question with no match in this repository is stopped first and isn\'t counted.';
