import { useCallback, useEffect, useMemo } from 'react';
import { useSearchParams } from 'react-router';
import { List, Network } from 'lucide-react';
import { hasCode } from '@/api/errors';
import type { EdgeType, GraphResponse, NodeType } from '@/api/types';
import { classicDefault, countByType, defaultFilter, idsKey, isExternalId, layoutEdges, matchCounts, matchesQuery, neighbourhood, nodeSize, visibleGraph } from '@/components/graph/adapter';
import { GraphCanvas } from '@/components/graph/GraphCanvas';
import { GraphSearch } from '@/components/graph/GraphSearch';
import { GRAPH_SEARCH_ID, isTypingTarget } from '@/components/graph/graphSearchKeys';
import { NODE_STYLES } from '@/components/graph/graphStyles';
import { Inspector } from '@/components/graph/Inspector';
import { parseScope, SCOPE_DEFAULTS, scopeCounts, scopeParam, type Scope } from '@/components/graph/scope';
import { layoutCacheKey } from '@/components/graph/layoutVersion';
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
import { dependenciesParam, dependentsParam, parseDependencies, parseDependents, parseEdgeTypes, parseNodeTypes, parseQuery, queryParam } from './graphParams';
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
  const query = parseQuery(params.get('q'));
  // Each is on by default and written as `dependents=0` / `dependencies=0` only when off; they matter only while a name filter is set.
  const includeDependents = parseDependents(params.get('dependents'));
  const includeDependencies = parseDependencies(params.get('dependencies'));
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
    () => visibleGraph(graph, { scope, query, includeDependents, includeDependencies, nodeTypes: new Set(nodeKey ? (nodeKey.split(',') as NodeType[]) : []), edgeTypes: new Set(edgeKey ? (edgeKey.split(',') as EdgeType[]) : []) }),
    [graph, nodeKey, edgeKey, scope, query, includeDependents, includeDependencies],
  );
  // "Showing 12 of 87": the nodes of the selected types and scope, and how many of them the name filter keeps.
  const matches = useMemo(
    () => matchCounts(graph, { scope, query, nodeTypes: new Set(nodeKey ? (nodeKey.split(',') as NodeType[]) : []) }),
    [graph, nodeKey, scope, query],
  );
  // What each scope option would reveal under the current node-type filter, so an empty view explains itself.
  const scopeOptionCounts = useMemo(() => scopeCounts(graph.nodes, new Set(nodeKey ? (nodeKey.split(',') as NodeType[]) : [])), [graph, nodeKey]);
  const nodesById = useMemo(() => new Map(graph.nodes.map((n) => [n.id, n])), [graph]);
  const selected = selectedId && visible.nodes.some((n) => n.id === selectedId) ? selectedId : selectedId && nodesById.has(selectedId) ? selectedId : null;
  // A selected node the name filter hides stays selected (the inspector says so), but must not dim everything that is shown.
  const selectedIsDrawn = selected !== null && visible.nodes.some((n) => n.id === selected);
  const hiddenByFilter = selected !== null && query !== '' && !selectedIsDrawn && !matchesQuery(nodesById.get(selected) ?? { name: '' }, query);
  const highlight = useMemo(() => (selected && selectedIsDrawn ? neighbourhood(visible.edges, selected) : null), [selected, selectedIsDrawn, visible.edges]);

  // The visible set is a function of the graph, the two type filters and the scope, so those key the layout cache:
  // a scope change lays out again, and going back to a set seen before reuses its layout.
  // It also carries the exact visible ids: a name filter produces sets of the same size with different members.
  const layoutKey = layoutCacheKey(`${analysisId}|${nodeKey}|${edgeKey}|${scope}|${idsKey(visible.nodes, visible.edges)}`);
  const layoutInput = useMemo(
    () => ({ nodes: visible.nodes.map((n) => ({ id: n.id, ...nodeSize(n) })), edges: layoutEdges(visible.edges) }),
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
  const reset = () => update({ nodes: null, edges: null, node: null, scope: null, q: null, dependents: null, dependencies: null });
  const setQuery = useCallback((term: string) => update({ q: queryParam(term) }), [update]);
  const setDependents = useCallback((on: boolean) => update({ dependents: dependentsParam(on) }), [update]);
  const setDependencies = useCallback((on: boolean) => update({ dependencies: dependenciesParam(on) }), [update]);
  const clearQuery = () => setQuery('');

  // "Showing 3 matches, 5 that depend on them and 2 that they depend on": every drawn node is a match except the other two groups.
  // Only the groups that are switched on are named; with both off it is the plain "Showing 12 of 87 nodes".
  const dependentCount = visible.dependentIds.size;
  const dependencyCount = visible.dependencyIds.size;
  const matchedCount = visible.nodes.length - dependentCount - dependencyCount;
  const extras = [
    ...(includeDependents ? [`${formatNumber(dependentCount)} that ${dependentCount === 1 ? 'depends' : 'depend'} on ${matchedCount === 1 ? 'it' : 'them'}`] : []),
    ...(includeDependencies ? [`${formatNumber(dependencyCount)} that ${matchedCount === 1 ? 'it depends' : 'they depend'} on`] : []),
  ];
  const countText = !query
    ? `${plural(visible.nodes.length, 'node')}, `
    : extras.length > 0
      ? `Showing ${plural(matchedCount, 'match', 'matches')}${extras.length === 1 ? ` and ${extras[0]}` : `, ${extras[0]} and ${extras[1]}`}, `
      : `Showing ${formatNumber(matches.shown)} of ${formatNumber(matches.total)} nodes, `;
  const searchField = (
    <GraphSearch
      value={query}
      onCommit={setQuery}
      includeDependents={includeDependents}
      onIncludeDependents={setDependents}
      includeDependencies={includeDependencies}
      onIncludeDependencies={setDependencies}
    />
  );

  // `/` jumps to the name filter, unless the person is typing somewhere (this tab only: the listener goes with the page).
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey || isTypingTarget(e.target)) return;
      const field = document.getElementById(GRAPH_SEARCH_ID);
      if (!field) return;
      e.preventDefault();
      field.focus();
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);
  const setScope = (next: Scope) => update({ scope: scopeParam(next, SCOPE_DEFAULTS.architecture), node: null });

  // The name filter keeps nothing: say what was asked for, and the two ways out.
  const typeWords = nodeTypes.map((t) => NODE_STYLES[t].plural.toLowerCase());
  const noMatch = (
    <EmptyState
      title={`No ${typeWords.length > 1 ? `${typeWords.slice(0, -1).join(', ')} or ${typeWords[typeWords.length - 1]}` : typeWords[0] ?? 'nodes'} match “${query}”`}
      action={
        <div className="flex flex-wrap gap-xs">
          <Button onClick={clearQuery}>Clear filter</Button>
          {scope !== 'all' ? <Button onClick={() => setScope('all')}>Show all</Button> : null}
        </div>
      }
    >
      The filter looks at names only, inside the node types and the {scope === 'tests' ? 'Tests' : scope === 'working' ? 'Working' : 'All'} scope you have selected.
      {scope === 'all' ? '' : ' Show all includes test code.'}
    </EmptyState>
  );

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
        <div>
          <p className="text-sm text-muted" aria-live="polite" aria-atomic="true">
            {countText}
            {plural(visible.edges.length, 'relationship')}
          </p>
          {dependentCount + dependencyCount > 0 ? (
            <p className="text-xs text-muted" data-testid="graph-dependents-caption">
              {listView
                ? `${dependentCount > 0 ? 'Rows marked “Depends on a match” depend directly on a node whose name matches. ' : ''}${dependencyCount > 0 ? 'Rows marked “A match depends on it” are what a match depends on directly.' : ''}`.trim()
                : `Solid cards match the name.${dependentCount > 0 ? ' Dashed cards depend directly on a match.' : ''}${dependencyCount > 0 ? ' Dotted cards are what a match depends on directly.' : ''} Each has an arrow badge.`}
            </p>
          ) : null}
        </div>
        {listView ? searchField : null}
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
          {visible.nodes.length === 0 && !(query && matches.total > 0) ? (
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
            visible.nodes.length === 0 ? <div className="grid h-full place-items-center bg-canvas px-md">{noMatch}</div> : <NodeList nodes={visible.nodes} degree={visible.degree} selectedId={selected} onSelect={select} dependentIds={visible.dependentIds} dependencyIds={visible.dependencyIds} />
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
              toolbarExtra={searchField}
              query={query}
              dependentIds={visible.dependentIds}
              dependencyIds={visible.dependencyIds}
              emptyOverlay={visible.nodes.length === 0 ? noMatch : null}
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
        {selected ? (
          <Inspector
            analysisId={analysisId}
            nodeId={selected}
            nodesById={nodesById}
            onClose={() => select(null)}
            onSelect={select}
            notice={
              hiddenByFilter ? (
                <p className="flex flex-wrap items-center gap-x-xs gap-y-2xs text-sm text-muted" role="status">
                  Hidden by the filter.
                  <button type="button" className="link" onClick={clearQuery}>
                    Clear filter
                  </button>
                </p>
              ) : null
            }
          />
        ) : null}
      </div>
    </div>
  );
}
