import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { List, Network } from 'lucide-react';
import { hasCode } from '@/api/errors';
import type { EdgeType, GraphResponse, NodeType } from '@/api/types';
import { countByType, defaultFilter, neighbourhood, nodeSize, visibleGraph } from '@/components/graph/adapter';
import { GraphCanvas } from '@/components/graph/GraphCanvas';
import { Inspector } from '@/components/graph/Inspector';
import { useLayout } from '@/components/graph/useLayout';
import { Button } from '@/components/ui/Button';
import { Spinner } from '@/components/ui/Spinner';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useUpgrade } from '@/hooks/upgradeContext';
import { useGraph } from '@/hooks/useWorkspace';
import { formatNumber, plural } from '@/lib/format';
import { useWorkspace } from '../workspaceContext';
import { GraphFilters, GraphUpcoming } from './GraphFilters';
import { parseEdgeTypes, parseNodeTypes } from './graphParams';
import { NodeList } from './NodeList';

export default function ArchitecturePage() {
  useDocumentTitle('Architecture');
  const { analysisId, ready } = useWorkspace();
  // One fetch of the whole graph; the multi-select filters apply client-side (the V0.1 parameters take one value each).
  const graph = useGraph(analysisId, ready);
  const { openUpgrade } = useUpgrade();

  if (graph.isPending) {
    return (
      <div className="grid flex-1 place-items-center bg-canvas p-xl text-muted" role="status">
        <span className="flex items-center gap-xs">
          <Spinner size={16} /> Loading the graph
        </span>
      </div>
    );
  }
  if (graph.isError) {
    if (hasCode(graph.error, 'PREMIUM_FEATURE')) {
      return (
        <div className="px-md sm:px-lg">
          <ErrorState error={graph.error} />
          <Button onClick={() => openUpgrade('This graph view')}>See Premium</Button>
        </div>
      );
    }
    return (
      <div className="px-md sm:px-lg">
        <ErrorState error={graph.error} onRetry={() => void graph.refetch()} />
      </div>
    );
  }
  return <ArchitectureView analysisId={analysisId} graph={graph.data} />;
}

function ArchitectureView({ analysisId, graph }: { analysisId: string; graph: GraphResponse }) {
  const [params, setParams] = useSearchParams();
  const defaults = useMemo(() => defaultFilter(graph), [graph]);
  const nodeTypes = parseNodeTypes(params.get('nodes')) ?? defaults.nodeTypes;
  const edgeTypes = parseEdgeTypes(params.get('edges')) ?? defaults.edgeTypes;
  const selectedId = params.get('node');
  const listView = params.get('view') === 'list';
  const counts = useMemo(() => countByType(graph), [graph]);

  const update = useCallback(
    (changes: Record<string, string | null>) => {
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          for (const [k, v] of Object.entries(changes)) {
            if (v === null) next.delete(k);
            else next.set(k, v);
          }
          return next;
        },
        { replace: true },
      );
    },
    [setParams],
  );

  const nodeKey = nodeTypes.slice().sort().join(',');
  const edgeKey = edgeTypes.slice().sort().join(',');
  const visible = useMemo(
    () => visibleGraph(graph, { nodeTypes: new Set(nodeKey ? (nodeKey.split(',') as NodeType[]) : []), edgeTypes: new Set(edgeKey ? (edgeKey.split(',') as EdgeType[]) : []) }),
    [graph, nodeKey, edgeKey],
  );
  const nodesById = useMemo(() => new Map(graph.nodes.map((n) => [n.id, n])), [graph]);
  const selected = selectedId && visible.nodes.some((n) => n.id === selectedId) ? selectedId : selectedId && nodesById.has(selectedId) ? selectedId : null;
  const highlight = useMemo(() => (selected ? neighbourhood(visible.edges, selected) : null), [selected, visible.edges]);

  const layoutKey = `${analysisId}|${nodeKey}|${edgeKey}|${visible.nodes.length}|${visible.edges.length}`;
  const layoutInput = useMemo(
    () => ({ nodes: visible.nodes.map((n) => ({ id: n.id, ...nodeSize(n) })), edges: visible.edges.map((e) => ({ id: e.id, source: e.source, target: e.target })) }),
    [visible],
  );
  const layout = useLayout(layoutKey, layoutInput);

  const select = useCallback((id: string | null) => update({ node: id }), [update]);
  const reset = () => update({ nodes: null, edges: null, node: null });

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="border-b border-rule bg-paper px-md sm:px-lg">
        <GraphFilters
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          counts={counts}
          onNodeTypes={(t) => update({ nodes: t.join(','), node: null })}
          onEdgeTypes={(t) => update({ edges: t.join(',') })}
        />
      </div>
      <div className="flex flex-wrap items-center justify-between gap-x-lg gap-y-xs border-b border-rule bg-paper px-md py-2xs sm:px-lg">
        <p className="text-sm text-muted" aria-live="polite">
          {plural(visible.nodes.length, 'node')}, {plural(visible.edges.length, 'relationship')}
        </p>
        <GraphUpcoming onReset={reset} />
        <div className="flex gap-2xs" role="group" aria-label="View">
          <button type="button" className="btn btn-quiet btn-sm" aria-pressed={!listView} onClick={() => update({ view: null })}>
            <Network size={14} aria-hidden="true" /> Graph
          </button>
          <button type="button" className="btn btn-quiet btn-sm" aria-pressed={listView} onClick={() => update({ view: 'list' })}>
            <List size={14} aria-hidden="true" /> List
          </button>
        </div>
      </div>

      <div className="relative flex min-h-0 flex-1 max-lg:h-[72dvh] max-lg:flex-none">
        <div className="relative min-w-0 flex-1">
          {visible.nodes.length === 0 ? (
            <div className="grid h-full place-items-center bg-canvas px-md">
              <EmptyState title="Nothing matches these filters" action={<Button onClick={reset}>Reset filters</Button>}>
                Turn on a node type above, or go back to the default view of {defaults.nodeTypes.map((t) => t.toLowerCase()).join(' and ')} nodes.
              </EmptyState>
            </div>
          ) : listView ? (
            <NodeList nodes={visible.nodes} degree={visible.degree} selectedId={selected} onSelect={select} />
          ) : layout.error ? (
            <div className="grid h-full place-items-center px-md">
              <ErrorState error={null} />
            </div>
          ) : layout.positions ? (
            <GraphCanvas
              nodes={visible.nodes}
              edges={visible.edges}
              positions={layout.positions}
              layoutKey={layoutKey}
              selectedId={selected}
              highlight={highlight}
              onSelect={select}
              label={`Architecture graph: ${plural(visible.nodes.length, 'node')}. Use Tab to move between nodes and Enter to inspect one.`}
            />
          ) : (
            <div className="grid h-full place-items-center bg-canvas text-muted" role="status">
              <span className="flex items-center gap-xs">
                <Spinner size={16} /> Arranging {formatNumber(visible.nodes.length)} nodes
              </span>
            </div>
          )}
        </div>
        {selected ? <Inspector analysisId={analysisId} nodeId={selected} nodesById={nodesById} onClose={() => select(null)} onSelect={select} /> : null}
      </div>
    </div>
  );
}
