import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import { GitBranch, Search } from 'lucide-react';
import { hasCode } from '@/api/errors';
import type { EdgeType, EntitySummary, GraphNode, GraphResponse, NodeRef } from '@/api/types';
import { isExternalId } from '@/components/graph/adapter';
import { Glyph } from '@/components/graph/Glyph';
import { NODE_STYLES } from '@/components/graph/graphStyles';
import { ScopeControl } from '@/components/graph/ScopeControl';
import { inScope, parseScope, SCOPE_DEFAULTS, scopeCounts, scopeParam, type Scope } from '@/components/graph/scope';
import { ComingSoon } from '@/components/ui/Locked';
import { Skeleton, SkeletonRows } from '@/components/ui/Skeleton';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useClassDetail, useGraph, useNode } from '@/hooks/useWorkspace';
import { humanize } from '@/lib/format';
import { useWorkspace } from '../workspaceContext';

type Item = { id: string; name: string; type: GraphNode['type']; external?: boolean; qualifiedName?: string; file?: string; test?: boolean };

function ItemList({ title, items, known, onSelect, empty = 'None' }: { title: string; items: Item[]; known: Set<string>; onSelect: (id: string) => void; empty?: string }) {
  return (
    <section className="grid content-start gap-2xs">
      <h3 className="flex items-baseline justify-between text-sm text-muted">
        {title} <span className="num text-xs">{items.length}</span>
      </h3>
      {items.length === 0 ? (
        <p className="text-sm text-muted">{empty}</p>
      ) : (
        <ul className="grid gap-3xs">
          {items.map((it) => {
            const s = NODE_STYLES[it.type];
            const clickable = !it.external && known.has(it.id);
            return (
              <li key={it.id} className="flex items-baseline gap-xs text-sm">
                <span style={{ color: s.color }} className="translate-y-[2px]"><Glyph glyph={s.glyph} size={12} /></span>
                {clickable ? (
                  <button type="button" className="link text-left" onClick={() => onSelect(it.id)}>{it.name}</button>
                ) : (
                  <span className={it.external ? 'num break-all text-xs text-ink-2' : 'text-ink'}>{it.external ? (it.qualifiedName ?? it.name) : it.name}</span>
                )}
                {it.external ? <span className="chip ml-auto" data-tone="quiet">External</span> : null}
                {it.test ? <span className="chip ml-auto">Test</span> : null}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}

const asItems = (refs: NodeRef[], byId: Map<string, GraphNode>): Item[] => refs.map((r) => ({ id: r.id, name: r.name, type: r.type, external: r.external, qualifiedName: r.qualifiedName, test: !r.external && !!byId.get(r.id)?.test }));

function fromGraph(graph: GraphResponse, byId: Map<string, GraphNode>, id: string, type: EdgeType, direction: 'in' | 'out'): Item[] {
  const out: Item[] = [];
  for (const e of graph.edges) {
    if (e.type !== type) continue;
    const other = direction === 'in' ? (e.target === id ? e.source : null) : e.source === id ? e.target : null;
    const node = other ? byId.get(other) : undefined;
    if (node && !out.some((o) => o.id === node.id)) out.push({ id: node.id, name: node.name, type: node.type, test: node.test });
  }
  return out.sort((a, b) => a.name.localeCompare(b.name));
}

function Header({ name, type, kind, qualifiedName, file, line, test }: { name: string; type: GraphNode['type']; kind?: string; qualifiedName?: string; file?: string; line?: number; test?: boolean }) {
  const s = NODE_STYLES[type];
  return (
    <div className="grid gap-xs">
      <div className="flex items-center gap-xs">
        <span className="arch-node-glyph" style={{ '--node-ink': s.color, '--node-soft': s.soft } as React.CSSProperties}><Glyph glyph={s.glyph} /></span>
        <h2 className="min-w-0 break-words text-xl">{name}</h2>
        <span className="chip">{kind && kind !== type ? humanize(kind) : s.label}</span>
        {test ? <span className="chip" data-tone="ink">Test</span> : null}
      </div>
      {qualifiedName ? <p className="num break-all text-xs text-muted">{qualifiedName}</p> : null}
      {file ? <p className="num break-all text-xs text-muted">{file}{line ? `, line ${line}` : ''}</p> : null}
    </div>
  );
}

function ClassDetail({ analysisId, id, graph, byId, onSelect }: { analysisId: string; id: string; graph: GraphResponse; byId: Map<string, GraphNode>; onSelect: (id: string) => void }) {
  const detail = useClassDetail(analysisId, id, true);
  const known = useMemo(() => new Set(byId.keys()), [byId]);
  if (detail.isPending) return <SkeletonRows rows={6} label="Loading the class" />;
  if (detail.isError) return hasCode(detail.error, 'NODE_NOT_FOUND') ? <p className="text-muted">This class is not part of the analysed repository.</p> : <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />;
  const c = detail.data;
  const isTest = !!byId.get(id)?.test; // a class's methods are declared in its own file
  const methods: Item[] = c.methods.map((m: EntitySummary) => ({ id: m.id, name: `${m.name}()`, type: m.type, test: isTest }));
  return (
    <div className="grid gap-lg">
      <Header name={c.name} type="CLASS" kind={c.kind} qualifiedName={c.qualifiedName} file={c.file} line={c.line} test={isTest} />
      <div className="grid gap-lg md:grid-cols-2">
        <ItemList title="Extends" items={asItems(c.superclasses, byId)} known={known} onSelect={onSelect} />
        <ItemList title="Implements" items={asItems(c.interfaces, byId)} known={known} onSelect={onSelect} />
        <ItemList title="Depends on" items={asItems(c.dependencies, byId)} known={known} onSelect={onSelect} />
        <ItemList title="Depended on by" items={fromGraph(graph, byId, id, 'DEPENDS_ON', 'in')} known={known} onSelect={onSelect} />
        <ItemList title="Methods" items={methods} known={new Set()} onSelect={onSelect} />
      </div>
    </div>
  );
}

function InterfaceDetail({ analysisId, id, graph, byId, onSelect }: { analysisId: string; id: string; graph: GraphResponse; byId: Map<string, GraphNode>; onSelect: (id: string) => void }) {
  const node = useNode(analysisId, id, true);
  const known = useMemo(() => new Set(byId.keys()), [byId]);
  if (node.isPending) return <SkeletonRows rows={6} label="Loading the interface" />;
  if (node.isError) return hasCode(node.error, 'NODE_NOT_FOUND') ? <p className="text-muted">This interface is not part of the analysed repository.</p> : <ErrorState error={node.error} onRetry={() => void node.refetch()} />;
  const n = node.data;
  return (
    <div className="grid gap-lg">
      <Header name={n.name} type="INTERFACE" kind={n.kind} qualifiedName={n.qualifiedName} file={n.file} line={n.line} test={n.test} />
      <div className="grid gap-lg md:grid-cols-2">
        <ItemList title="Extends" items={fromGraph(graph, byId, id, 'EXTENDS', 'out')} known={known} onSelect={onSelect} />
        <ItemList title="Implemented by" items={fromGraph(graph, byId, id, 'IMPLEMENTS', 'in')} known={known} onSelect={onSelect} />
        <ItemList title="Depends on" items={fromGraph(graph, byId, id, 'DEPENDS_ON', 'out')} known={known} onSelect={onSelect} />
        <ItemList title="Depended on by" items={fromGraph(graph, byId, id, 'DEPENDS_ON', 'in')} known={known} onSelect={onSelect} />
      </div>
    </div>
  );
}

export default function RelationshipsPage() {
  useDocumentTitle('Relationships');
  const { analysisId, ready } = useWorkspace();
  const graph = useGraph(analysisId, ready);
  const [params, setParams] = useSearchParams();
  const [query, setQuery] = useState('');
  const selected = params.get('item');
  const scope = parseScope(params.get('scope'), SCOPE_DEFAULTS.relationships);

  const byId = useMemo(() => new Map((graph.data?.nodes ?? []).filter((n) => !isExternalId(n.id)).map((n) => [n.id, n])), [graph.data]);
  // Every class and interface, whatever the scope: "no classes at all" is decided on this list.
  const allTypes = useMemo(() => [...byId.values()].filter((n) => n.type === 'CLASS' || n.type === 'INTERFACE').sort((a, b) => a.name.localeCompare(b.name)), [byId]);
  // The browse list follows the scope. The detail panel does not: it describes real relationships, and hiding
  // "depended on by FooTest" would hide test coverage.
  const types = useMemo(() => allTypes.filter((n) => inScope(n, scope)), [allTypes, scope]);
  const optionCounts = useMemo(() => scopeCounts(allTypes, null), [allTypes]);
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    return q ? types.filter((t) => t.name.toLowerCase().includes(q) || (t.file ?? '').toLowerCase().includes(q)) : types;
  }, [types, query]);

  const select = (id: string) => setParams((prev) => { const next = new URLSearchParams(prev); next.set('item', id); return next; });
  const setScope = (value: Scope) => setParams((prev) => {
    const next = new URLSearchParams(prev);
    const param = scopeParam(value, SCOPE_DEFAULTS.relationships);
    if (param === null) next.delete('scope');
    else next.set('scope', param);
    return next;
  }, { replace: true });

  if (graph.isPending) return <div className="px-md py-lg sm:px-lg"><Skeleton className="h-64 w-full" /></div>;
  if (graph.isError) return <div className="px-md sm:px-lg"><ErrorState error={graph.error} onRetry={() => void graph.refetch()} /></div>;
  if (allTypes.length === 0) {
    return (
      <div className="mx-auto w-full max-w-[72rem] px-md sm:px-lg">
        <EmptyState title="No classes or interfaces here" icon={<GitBranch size={28} aria-hidden="true" />}>
          This repository is written without classes (functions and modules only), so there is no class hierarchy to browse. The Architecture tab shows its modules and functions instead.
        </EmptyState>
      </div>
    );
  }

  const selectedNode = selected ? byId.get(selected) : undefined;
  const hiddenByScope = !!selectedNode && !inScope(selectedNode, scope);

  return (
    <div className="mx-auto grid w-full max-w-[80rem] flex-1 gap-lg px-md py-lg sm:px-lg lg:grid-cols-[20rem_minmax(0,1fr)]">
      <section aria-label="Classes and interfaces" className="grid content-start gap-sm">
        <div className="relative">
          <label htmlFor="type-search" className="sr-only">Filter classes and interfaces</label>
          <Search size={16} aria-hidden="true" className="pointer-events-none absolute left-sm top-1/2 -translate-y-1/2 text-muted" />
          <input id="type-search" type="search" className="input pl-xl" placeholder="Filter by name or file" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <ScopeControl value={scope} onChange={setScope} counts={optionCounts} className="flex-wrap" />
        <p className="text-xs text-muted" aria-live="polite">{shown.length} of {types.length}</p>
        {types.length === 0 ? (
          <p className="text-sm text-muted">
            {scope === 'tests' ? 'No test classes or interfaces in this repository.' : 'Every class and interface here is test code.'}{' '}
            <button type="button" className="link" onClick={() => setScope('all')}>Show all</button>
          </p>
        ) : null}
        <ul className="grid max-h-[60dvh] gap-3xs overflow-auto pr-2xs lg:max-h-[calc(100dvh-16rem)]">
          {shown.map((t) => {
            const s = NODE_STYLES[t.type];
            return (
              <li key={t.id}>
                <button type="button" className="nav-item h-auto w-full py-xs text-left" aria-current={t.id === selected ? 'page' : undefined} onClick={() => select(t.id)}>
                  <span style={{ color: s.color }}><Glyph glyph={s.glyph} size={12} /></span>
                  <span className="grid min-w-0">
                    <span className="truncate">{t.name}</span>
                    {t.file ? <span className="num truncate text-xs font-normal text-muted">{t.file}</span> : null}
                  </span>
                </button>
              </li>
            );
          })}
        </ul>
      </section>
      <section aria-label="Relationship details" className="panel grid content-start gap-lg p-lg">
        {hiddenByScope ? (
          <p className="flex flex-wrap items-center gap-x-xs gap-y-2xs text-sm text-muted" role="status">
            This {selectedNode?.test ? 'test class' : 'class'} is hidden from the list by the {scope === 'tests' ? 'Tests' : 'Working'} scope.
            <button type="button" className="link" onClick={() => setScope('all')}>Show all</button>
          </p>
        ) : null}
        {!selectedNode ? (
          <EmptyState title="Pick a class or interface" icon={<GitBranch size={28} aria-hidden="true" />}>
            See what it extends and implements, what it depends on and what depends on it.
          </EmptyState>
        ) : selectedNode.type === 'CLASS' ? (
          <ClassDetail analysisId={analysisId} id={selectedNode.id} graph={graph.data} byId={byId} onSelect={select} />
        ) : (
          <InterfaceDetail analysisId={analysisId} id={selectedNode.id} graph={graph.data} byId={byId} onSelect={select} />
        )}
        <div className="flex flex-wrap gap-xs border-t border-rule pt-md">
          <ComingSoon description="Method-level dependencies are planned for a later version.">Method-level dependencies</ComingSoon>
          <ComingSoon description="Exploring several levels deep is planned for a later version.">Deeper exploration</ComingSoon>
        </div>
      </section>
    </div>
  );
}
