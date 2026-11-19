import { useCallback, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { List, Network } from 'lucide-react';
import { hasCode } from '@/api/errors';
import type { EdgeType, GraphResponse, NodeType } from '@/api/types';
import { classicDefault, countByType, defaultFilter, isExternalId, neighbourhood, nodeSize, visibleGraph } from '@/components/graph/adapter';
import { GraphCanvas } from '@/components/graph/GraphCanvas';
import { Inspector } from '@/components/graph/Inspector';
import { parseScope, SCOPE_DEFAULTS, scopeCounts, scopeParam, type Scope } from '@/components/graph/scope';
import { movedCount, overlayPositions, useManualPositions } from '@/components/graph/manualPositions';
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
  // The module default is judged against the tab's default scope, so changing the scope filters the same view instead of swapping it.
  const defaults = useMemo(() => defaultFilter(graph, SCOPE_DEFAULTS.architecture), [graph]);
  const classic = useMemo(() => classicDefault(graph), [graph]);
  // A URL with a `nodes` or `edges` parameter is an explicit view: whatever it leaves out is what it always was.
  const explicit = params.has('nodes') || params.has('edges');
  const nodeTypes = parseNodeTypes(params.get('nodes')) ?? (explicit ? classic : defaults).nodeTypes;
  const edgeTypes = parseEdgeTypes(params.get('edges')) ?? (explicit ? classic : defaults).edgeTypes;
  // Shown while the module default is what is drawn; any change to the selection writes a parameter and ends it.
  const moduleCaption = defaults.moduleDefault && !explicit;
  const scope = parseScope(params.get('scope'), SCOPE_DEFAULTS.architecture);
  const selectedId = params.get('node');
  const listView = params.get('view') === 'list';
  const counts = useMemo(() => countByType(graph), [graph]);
  const hasTests = useMemo(() => graph.nodes.some((n) => n.test && !isExternalId(n.id)), [graph]);

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
    () => visibleGraph(graph, { scope, nodeTypes: new Set(nodeKey ? (nodeKey.split(',') as NodeType[]) : []), edgeTypes: new Set(edgeKey ? (edgeKey.split(',') as EdgeType[]) : []) }),
    [graph, nodeKey, edgeKey, scope],
  );
  // What each scope option would reveal under the current node-type filter, so an empty view explains itself.
  const scopeOptionCounts = useMemo(() => scopeCounts(graph.nodes, new Set(nodeKey ? (nodeKey.split(',') as NodeType[]) : [])), [graph, nodeKey]);
  const nodesById = useMemo(() => new Map(graph.nodes.map((n) => [n.id, n])), [graph]);
  const selected = selectedId && visible.nodes.some((n) => n.id === selectedId) ? selectedId : selectedId && nodesById.has(selectedId) ? selectedId : null;
  const highlight = useMemo(() => (selected ? neighbourhood(visible.edges, selected) : null), [selected, visible.edges]);

  // The visible set is a function of the graph, the two type filters and the scope, so those key the layout cache:
  // a scope change lays out again, and going back to a set seen before reuses its layout.
  const layoutKey = `${analysisId}|${nodeKey}|${edgeKey}|${scope}|${visible.nodes.length}|${visible.edges.length}`;
  const layoutInput = useMemo(
    () => ({ nodes: visible.nodes.map((n) => ({ id: n.id, ...nodeSize(n) })), edges: visible.edges.map((e) => ({ id: e.id, source: e.source, target: e.target })) }),
    [visible],
  );
  const layout = useLayout(layoutKey, layoutInput);

  // Dragged positions are laid over the automatic layout after it (and its cache), so a filter or scope change
  // re-lays out the other nodes while the ones the user placed stay where they are.
  const { manual, move, reset: resetLayout } = useManualPositions(analysisId);
  const positions = useMemo(
    () => (layout.positions ? overlayPositions(layout.positions, manual, visible.nodes.map((n) => n.id)) : null),
    [layout.positions, manual, visible.nodes],
  );
  const moved = useMemo(() => movedCount(manual, visible.nodes.map((n) => n.id)) > 0, [manual, visible.nodes]);

  const select = useCallback((id: string | null) => update({ node: id }), [update]);
  const reset = () => update({ nodes: null, edges: null, node: null, scope: null });
  const setScope = (next: Scope) => update({ scope: scopeParam(next, SCOPE_DEFAULTS.architecture), node: null });

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="border-b border-rule bg-paper px-md sm:px-lg">
        <GraphFilters
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          counts={counts}
          scope={scope}
          scopeCounts={scopeOptionCounts}
          caption={moduleCaption ? ((counts.get('CLASS') ?? 0) + (counts.get('INTERFACE') ?? 0) > 0 ? 'Modules joined by DEPENDS_ON. Turn on Classes to look inside.' : 'Modules joined by DEPENDS_ON.') : null}
          onScope={setScope}
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
              {scope === 'tests' && !hasTests ? (
                <EmptyState title="No test code found in this repository" action={<Button onClick={() => setScope('all')}>Show all</Button>}>
                  Test code is recognised by folder and file names such as src/test, tests, *Test.java, test_*.py and *.spec.ts. Nothing in this analysis matches.
                </EmptyState>
              ) : scope === 'working' && scopeOptionCounts.all > 0 && scopeOptionCounts.working === 0 ? (
                <EmptyState title="Everything shown here is test code" action={<Button onClick={() => setScope('all')}>Show all</Button>}>
                  Working hides test code, and with the node types selected every node is a test. Show all to see them.
                </EmptyState>
              ) : (
                <EmptyState title="Nothing matches these filters" action={<Button onClick={reset}>Reset filters</Button>}>
                  Turn on a node type above, or go back to the default view of {defaults.nodeTypes.map((t) => t.toLowerCase()).join(' and ')} nodes.
                </EmptyState>
              )}
            </div>
          ) : listView ? (
            <NodeList nodes={visible.nodes} degree={visible.degree} selectedId={selected} onSelect={select} />
          ) : layout.error ? (
            <div className="grid h-full place-items-center px-md">
              <ErrorState error={null} />
            </div>
          ) : positions ? (
            <GraphCanvas
              nodes={visible.nodes}
              edges={visible.edges}
              positions={positions}
              layoutKey={layoutKey}
              selectedId={selected}
              highlight={highlight}
              onSelect={select}
              onMove={move}
              onResetLayout={resetLayout}
              moved={moved}
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
