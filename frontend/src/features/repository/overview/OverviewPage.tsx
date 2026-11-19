import { useMemo } from 'react';
import { ArrowRight } from 'lucide-react';
import type { GraphResponse } from '@/api/types';
import { defaultFilter, nodeSize, visibleGraph } from '@/components/graph/adapter';
import { GraphCanvas } from '@/components/graph/GraphCanvas';
import { SCOPE_DEFAULTS } from '@/components/graph/scope';
import { useLayout } from '@/components/graph/useLayout';
import { ButtonLink } from '@/components/ui/Button';
import { Skeleton } from '@/components/ui/Skeleton';
import { Spinner } from '@/components/ui/Spinner';
import { ErrorState } from '@/components/ui/States';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useGraph, useRepository } from '@/hooks/useWorkspace';
import { formatNumber } from '@/lib/format';
import { useWorkspace } from '../workspaceContext';

const PREVIEW_LIMIT = 24;

function Preview({ analysisId, graph }: { analysisId: string; graph: GraphResponse }) {
  const view = useMemo(() => {
    // The preview is the Architecture tab's default view (working code); a repository that is only tests shows them.
    const d = defaultFilter(graph, SCOPE_DEFAULTS.architecture);
    const filter = { nodeTypes: new Set(d.nodeTypes), edgeTypes: new Set(d.edgeTypes) };
    const working = visibleGraph(graph, { ...filter, scope: 'working' });
    const full = working.nodes.length > 0 ? working : visibleGraph(graph, { ...filter, scope: 'all' });
    // A preview, not the graph: the most connected nodes only.
    const top = new Set([...full.nodes].filter((n) => (full.degree.get(n.id) ?? 0) > 0).sort((a, b) => (full.degree.get(b.id) ?? 0) - (full.degree.get(a.id) ?? 0)).slice(0, PREVIEW_LIMIT).map((n) => n.id));
    const edges = full.edges.filter((e) => top.has(e.source) && top.has(e.target));
    // Only nodes that still connect inside the preview, so it reads as structure.
    const linked = new Set(edges.flatMap((e) => [e.source, e.target]));
    const nodes = full.nodes.filter((n) => linked.has(n.id));
    return { nodes, edges, total: full.nodes.length };
  }, [graph]);
  const key = `preview|${analysisId}|${view.nodes.length}|${view.edges.length}`;
  const input = useMemo(() => ({ nodes: view.nodes.map((n) => ({ id: n.id, ...nodeSize(n) })), edges: view.edges.map((e) => ({ id: e.id, source: e.source, target: e.target })) }), [view]);
  const layout = useLayout(key, input);

  if (view.nodes.length === 0) return <p className="p-lg text-muted">This repository has no classes or modules to draw.</p>;
  return (
    <div className="h-[22rem]">
      {layout.positions ? (
        <GraphCanvas nodes={view.nodes} edges={view.edges} positions={layout.positions} layoutKey={key} interactive={false} label="Architecture preview (not interactive)" />
      ) : (
        <div className="grid h-full place-items-center bg-canvas text-muted" role="status">
          <span className="flex items-center gap-xs"><Spinner size={16} /> Arranging the preview</span>
        </div>
      )}
    </div>
  );
}

export default function OverviewPage() {
  useDocumentTitle('Overview');
  const { analysisId, ready } = useWorkspace();
  const repository = useRepository(analysisId, ready);
  const graph = useGraph(analysisId, ready);
  // The same rule as the Architecture tab's initial view decides what the preview shows, and so what the sentence under it says.
  const modulePreview = graph.data ? defaultFilter(graph.data, SCOPE_DEFAULTS.architecture).moduleDefault : false;

  return (
    <div className="mx-auto grid w-full max-w-[72rem] gap-xl px-md py-lg sm:px-lg">
      <section aria-labelledby="stats-heading" className="grid gap-md">
        <h2 id="stats-heading" className="sr-only">Repository in numbers</h2>
        {repository.isPending ? (
          <Skeleton className="h-24 w-full" />
        ) : repository.isError ? (
          <ErrorState error={repository.error} onRetry={() => void repository.refetch()} />
        ) : (
          <dl className="stats">
            {[
              ['Files', repository.data.files, 'in every language found'],
              ['Classes', repository.data.classes, 'enums and records included'],
              ['Methods and functions', repository.data.methods, 'of the repository’s own code'],
              ['Languages', repository.data.languages.length, 'detected'],
            ].map(([label, value, note]) => (
              <div key={label as string} className="stat">
                <dt className="text-sm text-muted">{label}</dt>
                <dd className="num text-3xl text-ink">{formatNumber(value as number)}</dd>
                <dd className="text-xs text-muted">{note}</dd>
              </div>
            ))}
          </dl>
        )}
      </section>

      <div className="grid gap-xl lg:grid-cols-[minmax(0,1fr)_18rem]">
        <section aria-labelledby="preview-heading" className="grid content-start gap-sm">
          <div className="flex flex-wrap items-baseline justify-between gap-sm">
            <h2 id="preview-heading" className="text-xl">Architecture</h2>
            <ButtonLink to="../architecture" size="sm" icon={<ArrowRight size={14} aria-hidden="true" />}>
              Open the graph
            </ButtonLink>
          </div>
          <div className="overflow-hidden rounded-card border border-rule">
            {graph.isPending ? (
              <Skeleton className="h-[22rem] w-full rounded-none" />
            ) : graph.isError ? (
              <div className="p-md"><ErrorState compact error={graph.error} onRetry={() => void graph.refetch()} /></div>
            ) : (
              <Preview analysisId={analysisId} graph={graph.data} />
            )}
          </div>
          <p className="text-sm text-muted">The most connected {modulePreview ? 'modules, joined by DEPENDS_ON' : 'classes'}, read top to bottom. Open the graph to filter, inspect and see everything.</p>
        </section>

        <section aria-labelledby="languages-heading" className="grid content-start gap-sm">
          <h2 id="languages-heading" className="text-xl">Languages</h2>
          {repository.isPending ? (
            <Skeleton className="h-32 w-full" />
          ) : repository.isError ? null : repository.data.languages.length === 0 ? (
            <p className="text-muted">No languages were detected.</p>
          ) : (
            <>
              <ol className="grid">
                {repository.data.languages.map((lang, i) => (
                  <li key={lang} className="flex items-baseline gap-sm border-b border-rule py-xs text-sm">
                    <span className="num w-6 text-xs text-muted">{i + 1}</span>
                    <span className="text-ink">{lang}</span>
                  </li>
                ))}
              </ol>
              <p className="text-xs text-muted">Ordered by number of files. Analysed languages add classes and functions to the graph; the rest are only counted.</p>
            </>
          )}
        </section>
      </div>
    </div>
  );
}
