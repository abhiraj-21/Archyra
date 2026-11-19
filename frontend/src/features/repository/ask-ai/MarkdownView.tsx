import { Fragment, useMemo, type ReactNode } from 'react';
import { Check, Copy } from 'lucide-react';
import { useState } from 'react';
import { parseBlocks, splitCitations, type Block, type Inline } from '@/lib/askAi';

/**
 * Renders a model answer as React elements built from the parsed blocks: text nodes, <code>, <strong>, <a>, lists and
 * code blocks. There is no HTML anywhere on this path (no dangerouslySetInnerHTML), so markup in the answer is shown as
 * text, and a link only exists for an http(s) URL (see `safeHref`).
 */
export function MarkdownView({ markdown, citationRefs, onCite }: { markdown: string; citationRefs: ReadonlySet<number>; onCite: (ref: number) => void }) {
  const blocks = useMemo(() => parseBlocks(markdown), [markdown]);
  return (
    <div className="grid gap-sm">
      {blocks.map((block, i) => (
        <BlockView key={i} block={block} citationRefs={citationRefs} onCite={onCite} />
      ))}
    </div>
  );
}

function BlockView({ block, citationRefs, onCite }: { block: Block; citationRefs: ReadonlySet<number>; onCite: (ref: number) => void }) {
  if (block.kind === 'code') return <CodeBlock language={block.language} value={block.value} />;
  if (block.kind === 'list') {
    const Tag = block.ordered ? 'ol' : 'ul';
    return (
      <Tag className={`grid gap-2xs pl-lg ${block.ordered ? 'list-decimal' : 'list-disc'}`}>
        {block.items.map((item, i) => (
          <li key={i}>
            <Inlines inlines={item} citationRefs={citationRefs} onCite={onCite} />
          </li>
        ))}
      </Tag>
    );
  }
  return (
    <p className="max-w-[70ch]">
      <Inlines inlines={block.inlines} citationRefs={citationRefs} onCite={onCite} />
    </p>
  );
}

function Inlines({ inlines, citationRefs, onCite }: { inlines: Inline[]; citationRefs: ReadonlySet<number>; onCite: (ref: number) => void }) {
  return (
    <>
      {inlines.map((inline, i) => {
        switch (inline.kind) {
          case 'code':
            return <code key={i} className="rounded-input bg-paper-3 px-3xs">{inline.value}</code>;
          case 'strong':
            return <strong key={i}>{inline.value}</strong>;
          case 'link':
            return (
              <a key={i} href={inline.href} target="_blank" rel="noopener noreferrer nofollow" className="link">
                {inline.value}
              </a>
            );
          case 'text':
            return <Fragment key={i}>{withCitations(inline.value, citationRefs, onCite)}</Fragment>;
        }
      })}
    </>
  );
}

/** `[n]` markers with a matching source become small superscript buttons; any other `[n]` stays text. */
function withCitations(text: string, refs: ReadonlySet<number>, onCite: (ref: number) => void): ReactNode[] {
  return splitCitations(text, refs).map((part, i) =>
    part.kind === 'text' ? (
      <Fragment key={i}>{part.value}</Fragment>
    ) : (
      <sup key={i}>
        <button type="button" className="cite" aria-label={`Show source ${part.ref}`} onClick={() => onCite(part.ref)}>
          {part.ref}
        </button>
      </sup>
    ),
  );
}

function CodeBlock({ language, value }: { language: string; value: string }) {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    void navigator.clipboard.writeText(value).then(
      () => {
        setCopied(true);
        window.setTimeout(() => setCopied(false), 1800);
      },
      () => setCopied(false),
    );
  };
  return (
    <div className="well grid gap-2xs p-sm">
      <div className="flex items-center justify-between gap-sm text-xs text-muted">
        <span>{language || 'code'}</span>
        <button type="button" className="btn btn-quiet btn-sm" onClick={copy} aria-label={copied ? 'Code copied' : 'Copy code'}>
          {copied ? <Check size={14} aria-hidden="true" /> : <Copy size={14} aria-hidden="true" />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
      <pre className="overflow-x-auto text-sm" tabIndex={0} aria-label={`${language || 'Code'} block`}>
        <code>{value}</code>
      </pre>
    </div>
  );
}
