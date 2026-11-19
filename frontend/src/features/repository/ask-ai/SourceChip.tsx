import { forwardRef } from 'react';
import { Link } from 'react-router';
import { ChevronDown, ChevronRight, ExternalLink } from 'lucide-react';
import type { ChatSource } from '@/api/types';
import { Glyph } from '@/components/graph/Glyph';
import { NODE_STYLES } from '@/components/graph/graphStyles';
import { useNode } from '@/hooks/useWorkspace';
import { encodeNodeId } from '@/lib/encode';
import { sourceLabel, sourceLocation } from '@/lib/askAi';

/** A cited node: type glyph and name (colour is never the only signal). Activating it expands the source. */
export const SourceChip = forwardRef<HTMLButtonElement, { source: ChatSource; expanded: boolean; panelId: string; onToggle: () => void }>(function SourceChip(
  { source, expanded, panelId, onToggle },
  ref,
) {
  const style = NODE_STYLES[source.type];
  return (
    <button ref={ref} type="button" className="chip" data-tone="ink" aria-expanded={expanded} aria-controls={panelId} aria-label={sourceLabel(source)} onClick={onToggle}>
      <span style={{ color: style.color }}>
        <Glyph glyph={style.glyph} size={12} />
      </span>
      <span className="num text-muted">{source.ref}</span>
      {source.name}
      {expanded ? <ChevronDown size={12} aria-hidden="true" /> : <ChevronRight size={12} aria-hidden="true" />}
    </button>
  );
});

/** Where the source is, the excerpt the model saw (text only), and a link to the node in the graph when there is one to show. */
export const SourcePanel = forwardRef<HTMLElement, { source: ChatSource; panelId: string; analysisId: string }>(function SourcePanel({ source, panelId, analysisId }, ref) {
  const location = sourceLocation(source);
  return (
    <section ref={ref} id={panelId} tabIndex={-1} className="well grid gap-xs p-sm" aria-label={`Source ${source.ref}: ${source.name}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-xs">
        <p className="min-w-0 break-all text-sm">
          <span className="font-medium text-ink">{source.name}</span>
          {location ? <span className="num text-muted"> · {location}</span> : null}
        </p>
        <GraphLink source={source} analysisId={analysisId} />
      </div>
      {source.excerpt ? (
        <pre className="overflow-x-auto text-sm" tabIndex={0} aria-label={`Excerpt of ${source.name}`}>
          <code>{source.excerpt}</code>
        </pre>
      ) : (
        <p className="text-xs text-muted">Cited from the graph: no code excerpt for this node.</p>
      )}
    </section>
  );
});

/**
 * The Architecture tab can show a class, an interface or a module directly. A method goes to its owning class (its parent, read with
 * the existing node hook). A function's parent is a file, which that view does not browse, so it gets no link.
 */
function GraphLink({ source, analysisId }: { source: ChatSource; analysisId: string }) {
  const needsParent = source.type === 'METHOD';
  const node = useNode(analysisId, needsParent ? source.id : null, true);
  const target = source.type === 'CLASS' || source.type === 'INTERFACE' ? source.id : needsParent ? (node.data?.parentId ?? null) : null;
  // A module is inspected on the Architecture tab too (it selects the node whether or not the module view is drawn); the repository
  // node is the whole analysis, so there is nothing to open.
  const isModule = source.type === 'MODULE' && source.id.startsWith('module:');
  if (isModule) {
    return (
      <Link to={`../architecture?node=${encodeNodeId(source.id)}`} className="link inline-flex items-center gap-3xs text-sm">
        Open in graph <ExternalLink size={12} aria-hidden="true" />
      </Link>
    );
  }
  if (!target || (!target.startsWith('class:') && !target.startsWith('interface:'))) return null;
  return (
    <Link to={`../architecture?node=${encodeNodeId(target)}`} className="link inline-flex items-center gap-3xs text-sm">
      Open in graph <ExternalLink size={12} aria-hidden="true" />
    </Link>
  );
}
