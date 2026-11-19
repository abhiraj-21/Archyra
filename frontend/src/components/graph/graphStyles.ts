import type { EdgeType, NodeType } from '@/api/types';

/**
 * The single definition of how graph types look (spec 8.6). Colours are token references only. Colour is never
 * the only signal: every node type has its own glyph shape, every edge type its own dash pattern and end marker.
 * The legend, the minimap, the canvas and the node list all read from here.
 */
export type Glyph = 'square' | 'ring' | 'dot' | 'diamond' | 'page' | 'stack' | 'hexagon';

export interface NodeStyle {
  label: string;
  plural: string;
  color: string;
  soft: string;
  glyph: Glyph;
}

export const NODE_STYLES: Record<NodeType, NodeStyle> = {
  CLASS: { label: 'Class', plural: 'Classes', color: 'var(--color-node-class)', soft: 'var(--color-node-class-soft)', glyph: 'square' },
  INTERFACE: { label: 'Interface', plural: 'Interfaces', color: 'var(--color-node-interface)', soft: 'var(--color-node-interface-soft)', glyph: 'ring' },
  METHOD: { label: 'Method', plural: 'Methods', color: 'var(--color-node-method)', soft: 'var(--color-node-method-soft)', glyph: 'dot' },
  FUNCTION: { label: 'Function', plural: 'Functions', color: 'var(--color-node-function)', soft: 'var(--color-node-function-soft)', glyph: 'diamond' },
  FILE: { label: 'File', plural: 'Files', color: 'var(--color-node-file)', soft: 'var(--color-node-file-soft)', glyph: 'page' },
  MODULE: { label: 'Module', plural: 'Modules', color: 'var(--color-node-module)', soft: 'var(--color-node-module-soft)', glyph: 'stack' },
  REPOSITORY: { label: 'Repository', plural: 'Repositories', color: 'var(--color-node-repository)', soft: 'var(--color-node-repository-soft)', glyph: 'hexagon' },
};

export type Marker = 'arrow' | 'open' | 'triangle' | 'diamond' | 'none';

export interface EdgeStyle {
  label: string;
  /** What the edge means, for the legend and screen readers. */
  meaning: string;
  /** An extra line under the legend entry, where the drawing needs a word of explanation. */
  note?: string;
  color: string;
  dash: string | undefined;
  width: number;
  markerEnd: Marker;
  markerStart: Marker;
}

export const EDGE_STYLES: Record<EdgeType, EdgeStyle> = {
  DEPENDS_ON: { label: 'Depends on', meaning: 'uses another class or module', note: 'Arrow points at the node that depends on it. Foundations are on top.', color: 'var(--color-edge-depends)', dash: undefined, width: 1.75, markerEnd: 'arrow', markerStart: 'none' },
  CALLS: { label: 'Calls', meaning: 'a method calls another method', color: 'var(--color-edge-calls)', dash: '6 4', width: 1.5, markerEnd: 'arrow', markerStart: 'none' },
  IMPORTS: { label: 'Imports', meaning: 'a file imports another file', color: 'var(--color-edge-imports)', dash: '2 4', width: 1.5, markerEnd: 'open', markerStart: 'none' },
  EXTENDS: { label: 'Extends', meaning: 'inherits from a class', color: 'var(--color-edge-extends)', dash: undefined, width: 1.5, markerEnd: 'triangle', markerStart: 'none' },
  IMPLEMENTS: { label: 'Implements', meaning: 'implements an interface', color: 'var(--color-edge-implements)', dash: '6 4', width: 1.5, markerEnd: 'triangle', markerStart: 'none' },
  REFERENCES: { label: 'References', meaning: 'mentions a type in a field or signature', color: 'var(--color-edge-references)', dash: '8 3 2 3', width: 1.25, markerEnd: 'open', markerStart: 'none' },
  CONTAINS: { label: 'Contains', meaning: 'holds another element', color: 'var(--color-edge-contains)', dash: undefined, width: 1, markerEnd: 'none', markerStart: 'diamond' },
};

/** Filter order in the UI (repository nodes are context, not a filter choice). */
export const FILTER_NODE_TYPES: NodeType[] = ['CLASS', 'INTERFACE', 'METHOD', 'FUNCTION', 'FILE', 'MODULE'];
export const FILTER_EDGE_TYPES: EdgeType[] = ['DEPENDS_ON', 'CALLS', 'IMPORTS', 'EXTENDS', 'IMPLEMENTS', 'REFERENCES', 'CONTAINS'];

export const markerId = (type: EdgeType, end: 'start' | 'end') => `archyra-${end}-${type.toLowerCase()}`;
