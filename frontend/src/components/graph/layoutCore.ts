import dagre from '@dagrejs/dagre';

export interface LayoutInput {
  nodes: { id: string; width: number; height: number }[];
  edges: { id?: string; source: string; target: string }[];
}
export type Positions = Record<string, { x: number; y: number }>;
export interface LayoutResult {
  positions: Positions;
}

const GAP = 28;

/**
 * Layered top-to-bottom layout (dagre, Sugiyama). Each input edge runs from the node that goes ABOVE to the node that goes
 * below it; which end that is for an edge type is decided once, in `layoutEdges` / `rankOrder` (adapter.ts): foundations
 * (what is depended on, extended, implemented or referenced) on top, so repository → service → controller reads top-down.
 * This is the rank only: the arrow is drawn by `drawDirection`. A dependency cycle is broken by dagre's acyclic step, which
 * only changes the ranking. Nodes with no visible edge are packed in rows under the layered part instead of stretching its
 * first rank across the screen.
 */
export function computeLayout({ nodes, edges }: LayoutInput): LayoutResult {
  const connected = new Set<string>();
  for (const e of edges) {
    if (e.source === e.target) continue;
    connected.add(e.source);
    connected.add(e.target);
  }
  const positions: Positions = {};
  let bottom = 0;
  let left = 0;
  let right = 0;

  if (connected.size > 0) {
    const g = new dagre.graphlib.Graph({ multigraph: true });
    g.setGraph({ rankdir: 'TB', nodesep: 24, ranksep: 64, edgesep: 10, marginx: 0, marginy: 0, ranker: 'network-simplex' });
    g.setDefaultEdgeLabel(() => ({}));
    for (const n of nodes) if (connected.has(n.id)) g.setNode(n.id, { width: n.width, height: n.height });
    edges.forEach((e, i) => {
      if (e.source !== e.target && connected.has(e.source) && connected.has(e.target)) g.setEdge(e.source, e.target, {}, e.id ?? String(i));
    });
    dagre.layout(g);
    left = Infinity;
    right = -Infinity;
    for (const n of nodes) {
      if (!connected.has(n.id)) continue;
      const p = g.node(n.id);
      const x = p.x - n.width / 2;
      const y = p.y - n.height / 2;
      positions[n.id] = { x, y };
      left = Math.min(left, x);
      right = Math.max(right, x + n.width);
      bottom = Math.max(bottom, y + n.height);
    }
    bottom += 160;
  }

  const isolated = nodes.filter((n) => !connected.has(n.id));
  if (isolated.length) {
    const rowWidth = Math.max(right - left, Math.sqrt(isolated.length) * 200, 600);
    let x = left === Infinity ? 0 : left;
    const startX = x;
    let y = bottom;
    let rowHeight = 0;
    for (const n of isolated) {
      if (x > startX && x + n.width > startX + rowWidth) {
        x = startX;
        y += rowHeight + GAP;
        rowHeight = 0;
      }
      positions[n.id] = { x, y };
      x += n.width + GAP;
      rowHeight = Math.max(rowHeight, n.height);
    }
  }
  return { positions };
}
