import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { applyNodeChanges, MiniMap, Panel, ReactFlow, ReactFlowProvider, useReactFlow, type Edge, type NodeChange, type NodeMouseHandler } from '@xyflow/react';
import { CircleHelp, Expand, Map as MapIcon, Maximize, Minimize, Minus, Plus, RotateCcw, Shapes } from 'lucide-react';
import type { GraphEdge, GraphNode } from '@/api/types';
import { ArchNode, type ArchFlowNode } from './ArchNode';
import { EdgeMarkers } from './EdgeMarkers';
import { nodeRole, nodeSize, toFlowEdge } from './adapter';
import { EDGE_STYLES, markerId } from './graphStyles';
import { Legend } from './Legend';
import type { Positions } from './layoutCore';

const nodeTypes = { arch: ArchNode };

export interface GraphCanvasProps {
  nodes: GraphNode[];
  edges: (GraphEdge & { id: string })[];
  positions: Positions;
  /** Changes when the visible set changes, so the view refits. */
  layoutKey: string;
  selectedId?: string | null;
  /** Ids to keep at full strength; everything else dims. */
  highlight?: Set<string> | null;
  onSelect?: (id: string | null) => void;
  /** Final positions of nodes the user moved (drag end or keyboard step). Dragging itself never reaches the parent. */
  onMove?: (moves: Positions) => void;
  /** Drop every manual position and show the automatic layout again. */
  onResetLayout?: () => void;
  /** Whether any visible node has been moved by hand (enables Reset layout). */
  moved?: boolean;
  interactive?: boolean;
  /** Extra controls in the toolbar (the name filter), so they are there in full screen too. */
  toolbarExtra?: ReactNode;
  /** The active name filter and the visible nodes that are only there because they depend on a match, or because a match depends on them (see `visibleGraph`). */
  query?: string;
  dependentIds?: ReadonlySet<string>;
  dependencyIds?: ReadonlySet<string>;
  /** Shown over the canvas when there is nothing to draw (for example when a name filter matches nothing). */
  emptyOverlay?: ReactNode;
  label: string;
}

const GESTURES =
  'Drag a node to move it. Shift-drag on empty canvas draws a selection box; Ctrl or Cmd-click adds a node to the selection, and dragging any selected node moves the whole selection. ' +
  'With nodes selected, the arrow keys nudge them (hold Shift for bigger steps). Escape clears the selection. Enter inspects the focused node. Moves last until you reload; Reset layout undoes them.';

function Toolbar({ fullscreen, onFullscreen, legend, onLegend, minimap, onMinimap, moved, onReset, extra }: { fullscreen: boolean; onFullscreen: () => void; legend: boolean; onLegend: () => void; minimap: boolean; onMinimap: () => void; moved: boolean; onReset: () => void; extra?: ReactNode }) {
  const { zoomIn, zoomOut, fitView } = useReactFlow();
  return (
    <Panel position="top-left" className="graph-toolbar" aria-label="Graph view controls">
      <button type="button" className="btn btn-secondary btn-icon" onClick={() => void zoomIn({ duration: 200 })} aria-label="Zoom in" title="Zoom in">
        <Plus size={16} aria-hidden="true" />
      </button>
      <button type="button" className="btn btn-secondary btn-icon" onClick={() => void zoomOut({ duration: 200 })} aria-label="Zoom out" title="Zoom out">
        <Minus size={16} aria-hidden="true" />
      </button>
      <button type="button" className="btn btn-secondary btn-icon" onClick={() => void fitView({ duration: 300, padding: 0.15 })} aria-label="Fit to screen" title="Fit to screen">
        <Expand size={16} aria-hidden="true" />
      </button>
      <button type="button" className="btn btn-secondary btn-icon" onClick={onReset} disabled={!moved} aria-label="Reset layout" title={moved ? 'Reset layout: put every node back where the automatic layout placed it' : 'Reset layout (no node has been moved)'}>
        <RotateCcw size={16} aria-hidden="true" />
      </button>
      <button type="button" className="btn btn-secondary btn-icon" onClick={onFullscreen} aria-label={fullscreen ? 'Exit full screen' : 'Full screen'} aria-pressed={fullscreen} title={fullscreen ? 'Exit full screen' : 'Full screen'}>
        {fullscreen ? <Minimize size={16} aria-hidden="true" /> : <Maximize size={16} aria-hidden="true" />}
      </button>
      <button type="button" className="btn btn-secondary btn-icon max-sm:hidden" onClick={onMinimap} aria-label="Minimap" aria-pressed={minimap} title="Minimap">
        <MapIcon size={16} aria-hidden="true" />
      </button>
      <button type="button" className="btn btn-secondary btn-icon" onClick={onLegend} aria-label="Legend" aria-pressed={legend} title="Legend">
        <Shapes size={16} aria-hidden="true" />
      </button>
      <span className="btn btn-quiet btn-icon" role="img" tabIndex={0} aria-label={`Gestures. ${GESTURES}`} title={GESTURES}>
        <CircleHelp size={16} aria-hidden="true" />
      </span>
      {extra}
    </Panel>
  );
}

/**
 * Fits the layered part of the graph (nodes with a visible edge) whenever the visible set changes, so the structure
 * reads first; interactive canvases never fit below a legible zoom. Then, and whenever the selection changes, a
 * selected node that is off screen (a shared ?node= link, a pick from the list) is panned into view at the current
 * zoom. A node already on screen never moves the view.
 */
function FitAndReveal({ layoutKey, fitToken, focusIds, minZoom, selectedId, positions, nodes, highlight }: { layoutKey: string; fitToken: number; focusIds: string[]; minZoom: number; selectedId: string | null; positions: Positions; nodes: GraphNode[]; highlight: Set<string> | null }) {
  const { fitView, getViewport, setCenter } = useReactFlow();
  const fittedKey = useRef<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    const reveal = () => {
      if (cancelled || !selectedId) return;
      const node = nodes.find((n) => n.id === selectedId);
      const pos = positions[selectedId];
      const el = document.querySelector('.graph-canvas .react-flow');
      if (!node || !pos || !el) return;
      const size = nodeSize(node);
      const cx = pos.x + size.width / 2;
      const cy = pos.y + size.height / 2;
      const { x, y, zoom } = getViewport();
      const sx = cx * zoom + x;
      const sy = cy * zoom + y;
      const inX = sx > 40 && sx < el.clientWidth - 40;
      const inY = sy > 40 && sy < el.clientHeight - 40;
      if (inX && inY) return;
      // Aim at the node's neighbourhood when it fits, so its connections land on screen too.
      let tx = cx;
      let ty = cy;
      const group = [...(highlight ?? [])].map((id) => ({ id, p: positions[id], n: nodes.find((nn) => nn.id === id) })).filter((g) => g.p && g.n);
      if (group.length > 1) {
        const lefts = group.map((g) => (g.p as { x: number }).x);
        const rights = group.map((g) => (g.p as { x: number }).x + nodeSize(g.n as GraphNode).width);
        const tops = group.map((g) => (g.p as { y: number }).y);
        const bottoms = group.map((g) => (g.p as { y: number }).y + 44);
        const [l, r, t, b] = [Math.min(...lefts), Math.max(...rights), Math.min(...tops), Math.max(...bottoms)];
        if ((r - l) * zoom < el.clientWidth - 80) tx = (l + r) / 2;
        if ((b - t) * zoom < el.clientHeight - 80) ty = (t + b) / 2;
      }
      // Pan only along the axis that needs it: the layers stay where the fit put them otherwise.
      const centreX = (el.clientWidth / 2 - x) / zoom;
      const centreY = (el.clientHeight / 2 - y) / zoom;
      void setCenter(inX ? centreX : tx, inY ? centreY : ty, { zoom, duration: 300 });
    };
    const run = async () => {
      const fitKey = `${layoutKey}|${fitToken}`; // a new token (Reset layout) fits again without changing the visible set
      if (fittedKey.current !== fitKey) {
        await new Promise((r) => window.requestAnimationFrame(r));
        if (cancelled) return;
        await fitView({ padding: 0.12, duration: 0, maxZoom: 1.1, minZoom, nodes: focusIds.length ? focusIds.map((nid) => ({ id: nid })) : undefined });
        fittedKey.current = fitKey;
        await new Promise((r) => window.requestAnimationFrame(r));
      }
      reveal();
    };
    void run();
    return () => {
      cancelled = true;
    };
    // Runs when the visible set, a layout reset or the selection changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [layoutKey, fitToken, selectedId]);
  return null;
}

const NO_IDS: ReadonlySet<string> = new Set();

function Canvas({ nodes, edges, positions, layoutKey, selectedId = null, highlight = null, onSelect, onMove, onResetLayout, moved = false, interactive = true, toolbarExtra, emptyOverlay, query, dependentIds = NO_IDS, dependencyIds = NO_IDS, label }: GraphCanvasProps) {
  const wrapper = useRef<HTMLDivElement>(null);
  const [fullscreen, setFullscreen] = useState(false);
  const [legend, setLegend] = useState(false);
  const [minimap, setMinimap] = useState(true);
  const [fitToken, setFitToken] = useState(0);
  const [announcement, setAnnouncement] = useState('');
  const announceTimer = useRef<number | undefined>(undefined);
  const lastArrow = useRef(0);
  const dragged = useRef(false);

  useEffect(() => {
    const onChange = () => setFullscreen(document.fullscreenElement === wrapper.current);
    document.addEventListener('fullscreenchange', onChange);
    return () => document.removeEventListener('fullscreenchange', onChange);
  }, []);

  const toggleFullscreen = useCallback(() => {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void wrapper.current?.requestFullscreen?.();
  }, []);

  const baseNodes = useMemo<ArchFlowNode[]>(
    () =>
      nodes.map((n) => {
        const role = nodeRole(n.id, { dependentIds, dependencyIds }, query);
        return {
          id: n.id,
          type: 'arch',
          ...nodeSize(n),
          position: positions[n.id] ?? { x: 0, y: 0 },
          data: { node: n, dim: !!highlight && !highlight.has(n.id), selected: n.id === selectedId, role, query },
          // The role is in words too, so a screen reader hears what the dashed outline and the badge show.
          ariaLabel: `${n.name}, ${n.type.toLowerCase()}${role === 'match' ? ', matches the filter' : role === 'dependent' ? ', depends on a match' : role === 'dependency' ? ', a match depends on it' : ''}${n.id === selectedId ? ', selected' : ''}`,
          draggable: interactive,
          selectable: interactive,
          connectable: false,
        };
      }),
    [nodes, positions, highlight, selectedId, interactive, dependentIds, dependencyIds, query],
  );

  // React Flow works on its own copy of the nodes, so a drag updates only the moved nodes' state: the layout worker
  // never runs and the parent is told once, when the move is over. The copy follows `baseNodes` whenever the visible
  // set, a position or a highlight changes, keeping what only React Flow knows (selection, measured size).
  const [flowNodes, setFlowNodes] = useState<ArchFlowNode[]>(baseNodes);
  const [syncedFrom, setSyncedFrom] = useState(baseNodes);
  if (syncedFrom !== baseNodes) {
    const previous = new Map(flowNodes.map((n) => [n.id, n]));
    setSyncedFrom(baseNodes);
    setFlowNodes(baseNodes.map((b) => {
      const p = previous.get(b.id);
      return p ? { ...b, selected: p.selected, measured: p.measured } : b;
    }));
  }

  const nameById = useMemo(() => new Map(nodes.map((n) => [n.id, n.name])), [nodes]);
  const names = useRef(nameById);
  names.current = nameById;

  /** One polite message once a burst of arrow presses ends, so a held key does not read out every step. */
  const announce = useCallback((ids: string[]) => {
    window.clearTimeout(announceTimer.current);
    announceTimer.current = window.setTimeout(() => {
      const text = ids.length === 1 ? `Moved ${names.current.get(ids[0] as string) ?? 'node'}` : `Moved ${ids.length} nodes`;
      setAnnouncement((prev) => (prev === text ? `${text}\u200b` : text));
    }, 500);
  }, []);
  useEffect(() => () => window.clearTimeout(announceTimer.current), []);

  const onNodesChange = useCallback(
    (changes: NodeChange<ArchFlowNode>[]) => {
      const kept = changes.filter((c) => c.type !== 'remove'); // Backspace must never delete a node
      if (kept.length === 0) return;
      setFlowNodes((prev) => applyNodeChanges(kept, prev));
      // A move that is over (the drop, or one arrow-key step) becomes the node's manual position.
      const done: Positions = {};
      for (const c of kept) if (c.type === 'position' && c.dragging !== true && c.position) done[c.id] = c.position;
      const ids = Object.keys(done);
      if (ids.length > 0) {
        onMove?.(done);
        if (performance.now() - lastArrow.current < 400) announce(ids);
      }
    },
    [onMove, announce],
  );

  const clearSelection = useCallback(() => setFlowNodes((prev) => (prev.some((n) => n.selected) ? prev.map((n) => (n.selected ? { ...n, selected: false } : n)) : prev)), []);

  const flowEdges = useMemo<Edge[]>(
    () =>
      edges.map((e) => {
        const s = EDGE_STYLES[e.type];
        // Highlighting reads the data edge (both ends and the selection are symmetric in it); only the drawing is turned around.
        const dim = !!highlight && !(highlight.has(e.source) && highlight.has(e.target) && (e.source === selectedId || e.target === selectedId));
        return {
          ...toFlowEdge(e, nameById, positions),
          type: 'default',
          focusable: false,
          selectable: false,
          markerEnd: s.markerEnd !== 'none' ? markerId(e.type, 'end') : undefined,
          markerStart: s.markerStart !== 'none' ? markerId(e.type, 'start') : undefined,
          className: dim ? 'is-dim' : undefined,
          style: { stroke: s.color, strokeWidth: s.width, strokeDasharray: s.dash },
        };
      }),
    [edges, highlight, selectedId, positions, nameById],
  );

  const focusIds = useMemo(() => {
    const ids = new Set<string>();
    for (const e of edges) {
      if (e.source === e.target) continue;
      ids.add(e.source);
      ids.add(e.target);
    }
    return [...ids];
  }, [edges]);

  // A click inspects; a drag, or a Ctrl/Cmd/Shift-click that edits the selection, never does.
  const onNodeClick: NodeMouseHandler = useCallback(
    (event, node) => {
      if (dragged.current || event.metaKey || event.ctrlKey || event.shiftKey) return;
      onSelect?.(node.id);
    },
    [onSelect],
  );
  const endDrag = useCallback(() => {
    window.setTimeout(() => {
      dragged.current = false; // after the click that may follow the drop
    }, 0);
  }, []);

  return (
    <div
      ref={wrapper}
      className="graph-canvas"
      data-interactive={interactive || undefined}
      onKeyDown={(e) => {
        if (!interactive) return;
        if (e.key === 'Escape') {
          clearSelection();
          onSelect?.(null);
        }
        if (e.key.startsWith('Arrow')) lastArrow.current = performance.now();
        if (e.key === 'Enter' || e.key === ' ') {
          const id = (document.activeElement as HTMLElement | null)?.closest('.react-flow__node')?.getAttribute('data-id');
          if (id) {
            e.preventDefault();
            onSelect?.(id);
          }
        }
      }}
    >
      <EdgeMarkers />
      <ReactFlow
        nodes={flowNodes}
        edges={flowEdges}
        nodeTypes={nodeTypes}
        onNodesChange={interactive ? onNodesChange : undefined}
        onNodeClick={interactive ? onNodeClick : undefined}
        onNodeDragStart={() => {
          dragged.current = true;
        }}
        onNodeDragStop={endDrag}
        onPaneClick={interactive ? () => onSelect?.(null) : undefined}
        nodesDraggable={interactive}
        nodesConnectable={false}
        elementsSelectable={interactive}
        nodeDragThreshold={4}
        nodeClickDistance={4}
        multiSelectionKeyCode={['Meta', 'Control']}
        deleteKeyCode={null}
        ariaLabelConfig={{ 'node.a11yDescription.ariaLiveMessage': () => '' }}
        nodesFocusable={interactive}
        edgesFocusable={false}
        panOnDrag={interactive}
        zoomOnScroll={interactive}
        zoomOnPinch={interactive}
        zoomOnDoubleClick={false}
        panOnScroll={false}
        preventScrolling={interactive}
        onlyRenderVisibleElements
        minZoom={0.05}
        maxZoom={2}
        fitView
        fitViewOptions={{ padding: 0.15, maxZoom: 1.1 }}
        proOptions={{ hideAttribution: true }}
        aria-label={label}
      >
        <FitAndReveal layoutKey={layoutKey} fitToken={fitToken} focusIds={focusIds} minZoom={interactive ? 0.55 : 0.05} selectedId={interactive ? selectedId : null} positions={positions} nodes={nodes} highlight={highlight} />
        {interactive ? (
          <>
            <Toolbar
              fullscreen={fullscreen}
              onFullscreen={toggleFullscreen}
              legend={legend}
              onLegend={() => setLegend((v) => !v)}
              minimap={minimap}
              onMinimap={() => setMinimap((v) => !v)}
              moved={moved}
              extra={toolbarExtra}
              onReset={() => {
                clearSelection();
                onResetLayout?.();
                setFitToken((t) => t + 1);
              }}
            />
            {emptyOverlay ? (
              <Panel position="top-center" className="graph-empty-overlay">
                {emptyOverlay}
              </Panel>
            ) : null}
            {legend ? (
              <Panel position="top-right" className="graph-legend-panel">
                <Legend />
              </Panel>
            ) : null}
            {minimap ? (
              <MiniMap
                className="graph-minimap"
                pannable
                zoomable
                ariaLabel="Minimap of the graph"
                nodeClassName={(n) => `mm-${(n as ArchFlowNode).data.node.type}`}
                nodeBorderRadius={4}
              />
            ) : null}
          </>
        ) : null}
      </ReactFlow>
      <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">{announcement}</div>
    </div>
  );
}

/** The architecture graph on a plain themed canvas. No grid, cross or dot background: that look is banned. */
export function GraphCanvas(props: GraphCanvasProps) {
  return (
    <ReactFlowProvider>
      <Canvas {...props} />
    </ReactFlowProvider>
  );
}
