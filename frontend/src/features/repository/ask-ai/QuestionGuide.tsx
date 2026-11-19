import { Ban, Lightbulb } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { COUNTING_NOTE, WONT_WORK, type GuideExamples } from '@/lib/askGuide';
import type { UsageLabel } from '@/lib/askAi';

interface GuideProps {
  examples: GuideExamples;
  /** The real remaining count from `/usage` (never a literal); null while it loads. */
  usage: UsageLabel | null;
  /** Fills the composer. An example never sends. */
  onExample: (question: string) => void;
}

/**
 * What to ask, and what not to. Two parts: questions that work well, grouped, built from this repository's own names (each is a
 * button that fills the composer), and the kinds of question that will be refused or waste one of the user's questions, with the
 * honest rule for what counts. Shown in full in an empty conversation and as a collapsible panel at any other time.
 */
export function QuestionGuide({ examples, usage, onExample }: GuideProps) {
  return (
    <section className="grid gap-md" aria-label="What to ask guide">
      <div className="grid gap-sm">
        <h3 className="flex items-center gap-xs text-lg">
          <Lightbulb size={18} aria-hidden="true" /> Questions that work well
        </h3>
        <p className="max-w-[60ch] text-sm text-muted">An example fills the question box; you decide whether to send it.</p>
        {examples.groups.map((group) => (
          <div key={group.title} className="grid gap-2xs">
            <h4 className="text-sm font-medium text-ink-2">{group.title}</h4>
            <ul className="flex flex-wrap gap-xs">
              {group.examples.map((example) => (
                <li key={example.question}>
                  <button type="button" className="chip" data-tone="ink" onClick={() => onExample(example.question)}>
                    {example.question}
                  </button>
                </li>
              ))}
            </ul>
          </div>
        ))}
        {examples.placeholders ? (
          <p className="text-xs text-muted">
            Some names above (<span className="num">{examples.className}</span>, <span className="num">{examples.moduleName}</span>) are placeholders until this repository&rsquo;s graph is
            loaded. Use a name from the repository.
          </p>
        ) : null}
      </div>

      <div className="grid gap-sm">
        <h3 className="flex items-center gap-xs text-lg">
          <Ban size={18} aria-hidden="true" /> Questions that won&rsquo;t work, or will waste one of your questions
        </h3>
        <ul className="grid gap-xs text-sm">
          {WONT_WORK.map((item) => (
            <li key={item.title} className="grid gap-3xs">
              <span className="font-medium text-ink">{item.title}</span>
              <span className="text-muted">{item.detail}</span>
            </li>
          ))}
        </ul>
      </div>

      <p className="max-w-[70ch] text-sm text-ink-2">
        {COUNTING_NOTE}
        {usage ? <> You have <strong className="font-medium text-ink">{usage.text}</strong>.</> : null}
      </p>
    </section>
  );
}

/** The "What to ask" button next to the composer: a keyboard-operable disclosure for the guide panel. */
export function GuideToggle({ open, controls, onToggle }: { open: boolean; controls: string; onToggle: () => void }) {
  return (
    <Button size="sm" variant="quiet" icon={<Lightbulb size={14} aria-hidden="true" />} aria-expanded={open} aria-controls={controls} onClick={onToggle}>
      What to ask
    </Button>
  );
}
