import { useMemo } from 'react';
import { Link } from 'react-router';
import { Send } from 'lucide-react';
import { Glyph } from '@/components/graph/Glyph';
import { NODE_STYLES } from '@/components/graph/graphStyles';
import { ComingSoonBadge } from '@/components/ui/Chips';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useGraph } from '@/hooks/useWorkspace';
import { useWorkspace } from '../workspaceContext';

/** Example questions from Architecture.md (V1, Ask AI). */
const EXAMPLES = ['How does authentication work?', 'Where does a request to /users go?', 'What classes depend on PaymentService?', 'Explain this class', 'What is the role of this module?'];

/** Ask AI is V1 and not built: this page calls nothing. */
export default function AskAiPage() {
  useDocumentTitle('Ask AI');
  const { analysisId, ready } = useWorkspace();
  const graph = useGraph(analysisId, ready);

  // Three real nodes of this repository, to show how an answer's sources will look. No answer is generated.
  const sources = useMemo(() => {
    const nodes = graph.data?.nodes.filter((n) => n.type === 'CLASS' || n.type === 'INTERFACE' || n.type === 'MODULE') ?? [];
    return nodes.slice(0, 3);
  }, [graph.data]);

  return (
    <div className="mx-auto grid w-full max-w-[52rem] gap-xl px-md py-xl sm:px-lg">
      <header className="grid gap-sm">
        <div><ComingSoonBadge /></div>
        <h2 className="text-2xl">Ask questions about this repository</h2>
        <p className="max-w-[60ch] text-muted">
          Ask AI will answer from this repository’s graph and code, and cite the classes, files and modules it used. It is not built yet, for any plan.
        </p>
      </header>

      <section aria-labelledby="examples-heading" className="grid gap-sm">
        <h3 id="examples-heading" className="text-lg">Questions it is meant to answer</h3>
        <ul className="grid gap-xs">
          {EXAMPLES.map((q) => (
            <li key={q} className="border-b border-rule pb-xs text-ink-2">{q}</li>
          ))}
        </ul>
      </section>

      <form className="grid gap-xs" onSubmit={(e) => e.preventDefault()} aria-describedby="ask-ai-soon">
        <label htmlFor="ask-ai" className="field-label">Your question</label>
        <div className="flex flex-col gap-sm sm:flex-row">
          <input id="ask-ai" className="input input-lg flex-1" disabled placeholder="Ask AI is coming soon" />
          <button type="submit" className="btn btn-primary btn-lg" disabled>
            <Send size={16} aria-hidden="true" /> Ask
          </button>
        </div>
        <p id="ask-ai-soon" className="field-help">Nothing is sent: the AI service does not exist yet.</p>
      </form>

      {sources.length ? (
        <section aria-labelledby="sources-heading" className="well grid gap-sm p-lg">
          <h3 id="sources-heading" className="text-lg">Preview: how sources will appear</h3>
          <p className="text-sm text-muted">No answer was generated. Each answer will list the graph nodes it used as citations like these, which open that node in the graph.</p>
          <ul className="flex flex-wrap gap-xs">
            {sources.map((n) => {
              const s = NODE_STYLES[n.type];
              return (
                <li key={n.id}>
                  <Link to={`../architecture?node=${encodeURIComponent(n.id)}`} className="chip link-plain" data-tone="ink">
                    <span style={{ color: s.color }}><Glyph glyph={s.glyph} size={12} /></span>
                    {n.name}
                  </Link>
                </li>
              );
            })}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
