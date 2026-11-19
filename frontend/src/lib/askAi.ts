import type { ChatSource, UsageEntry } from '@/api/types';

/** The backend rejects longer messages (archyra.ai.limits.message-max-chars); the counter shows near it. */
export const MESSAGE_MAX_CHARS = 2000;
export const COUNTER_FROM_CHARS = 1800;

export function counterVisible(length: number): boolean {
  return length >= COUNTER_FROM_CHARS;
}

export function isSendable(text: string): boolean {
  const trimmed = text.trim();
  return trimmed.length > 0 && trimmed.length <= MESSAGE_MAX_CHARS;
}

// ---- citations ----

export type CitationPart = { kind: 'text'; value: string } | { kind: 'cite'; ref: number };

const MARKER = /\[(\d{1,3})\]/g;

/**
 * Splits text at `[n]` markers. Only a marker whose number is in `validRefs` becomes a citation; any other (`[99]`,
 * `[0]`, an array index) stays plain text, so a marker with no source is never a dead button.
 */
export function splitCitations(text: string, validRefs: ReadonlySet<number>): CitationPart[] {
  const parts: CitationPart[] = [];
  let last = 0;
  for (const match of text.matchAll(MARKER)) {
    const ref = Number(match[1]);
    if (!validRefs.has(ref)) continue;
    const start = match.index ?? 0;
    if (start > last) parts.push({ kind: 'text', value: text.slice(last, start) });
    parts.push({ kind: 'cite', ref });
    last = start + match[0].length;
  }
  if (last < text.length) parts.push({ kind: 'text', value: text.slice(last) });
  return parts;
}

// ---- the safe Markdown renderer's parsing: it produces data, never HTML ----

export type Inline =
  | { kind: 'text'; value: string }
  | { kind: 'code'; value: string }
  | { kind: 'strong'; value: string }
  | { kind: 'link'; href: string; value: string };

export type Block =
  | { kind: 'paragraph'; inlines: Inline[] }
  | { kind: 'list'; ordered: boolean; items: Inline[][] }
  | { kind: 'code'; language: string; value: string };

/** A link survives only with an http(s) URL; anything else (javascript:, data:, relative) is dropped to its text. */
export function safeHref(url: string): string | null {
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.href : null;
  } catch {
    return null;
  }
}

const INLINE = /(`[^`\n]+`)|(\*\*[^*\n]+\*\*)|(\[[^\]\n]+\]\([^)\s]+\))/g;
const LINK = /^\[([^\]]+)\]\(([^)]+)\)$/;

export function parseInline(text: string): Inline[] {
  const out: Inline[] = [];
  let last = 0;
  for (const match of text.matchAll(INLINE)) {
    const start = match.index ?? 0;
    if (start > last) out.push({ kind: 'text', value: text.slice(last, start) });
    const token = match[0];
    if (match[1]) out.push({ kind: 'code', value: token.slice(1, -1) });
    else if (match[2]) out.push({ kind: 'strong', value: token.slice(2, -2) });
    else {
      const link = LINK.exec(token);
      const href = link ? safeHref(link[2] ?? '') : null;
      out.push(link && href ? { kind: 'link', href, value: link[1] ?? '' } : { kind: 'text', value: link?.[1] ?? token });
    }
    last = start + token.length;
  }
  if (last < text.length) out.push({ kind: 'text', value: text.slice(last) });
  return out;
}

const FENCE = /^\s*```\s*([\w+#.-]*)\s*$/;
const LIST_ITEM = /^\s*([-*+]|\d+[.)])\s+(.*)$/;
const HEADING = /^\s*#{1,6}\s+(.*)$/;

/** Paragraphs, ordered and unordered lists, fenced code (also unclosed) and headings (shown as bold paragraphs). */
export function parseBlocks(markdown: string): Block[] {
  const lines = markdown.replace(/\r\n?/g, '\n').split('\n');
  const blocks: Block[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i] ?? '';
    const fence = FENCE.exec(line);
    if (fence) {
      const body: string[] = [];
      i++;
      while (i < lines.length && !FENCE.test(lines[i] ?? '')) body.push(lines[i++] ?? '');
      i++; // the closing fence (or the end)
      blocks.push({ kind: 'code', language: fence[1] ?? '', value: body.join('\n') });
      continue;
    }
    if (line.trim() === '') {
      i++;
      continue;
    }
    const item = LIST_ITEM.exec(line);
    if (item) {
      const ordered = /\d/.test(item[1] ?? '');
      const items: Inline[][] = [];
      while (i < lines.length) {
        const current = LIST_ITEM.exec(lines[i] ?? '');
        if (!current || /\d/.test(current[1] ?? '') !== ordered) break;
        items.push(parseInline(current[2] ?? ''));
        i++;
      }
      blocks.push({ kind: 'list', ordered, items });
      continue;
    }
    const heading = HEADING.exec(line);
    if (heading) {
      blocks.push({ kind: 'paragraph', inlines: [{ kind: 'strong', value: heading[1] ?? '' }] });
      i++;
      continue;
    }
    const paragraph: string[] = [];
    while (i < lines.length && (lines[i] ?? '').trim() !== '' && !FENCE.test(lines[i] ?? '') && !LIST_ITEM.test(lines[i] ?? '') && !HEADING.test(lines[i] ?? '')) {
      paragraph.push((lines[i++] ?? '').trim());
    }
    blocks.push({ kind: 'paragraph', inlines: parseInline(paragraph.join(' ')) });
  }
  return blocks;
}

// ---- sources ----

/** `src/A.java:41–77`, `src/A.java:41`, or just the file when there is no line. */
export function sourceLocation(source: Pick<ChatSource, 'file' | 'line' | 'endLine'>): string | null {
  if (!source.file) return null;
  if (source.line === null) return source.file;
  return source.endLine !== null && source.endLine > source.line ? `${source.file}:${source.line}–${source.endLine}` : `${source.file}:${source.line}`;
}

/** The accessible name of a source chip: its number, type and name. */
export function sourceLabel(source: Pick<ChatSource, 'ref' | 'type' | 'name'>): string {
  return `Source ${source.ref}: ${source.type.toLowerCase()} ${source.name}`;
}

// ---- limits and usage ----

const DEFAULT_RETRY_SECONDS = 30;
const MAX_RETRY_SECONDS = 300;

/** Seconds to wait after a 429: the server's Retry-After, kept within sane bounds, or a default when it sent none. */
export function retrySeconds(retryAfter: number | undefined): number {
  if (retryAfter === undefined || !Number.isFinite(retryAfter) || retryAfter <= 0) return DEFAULT_RETRY_SECONDS;
  return Math.min(MAX_RETRY_SECONDS, Math.ceil(retryAfter));
}

export const LOW_QUESTIONS_LEFT = 3;

export interface UsageLabel {
  text: string;
  tone: 'normal' | 'warn' | 'full';
}

/** "17 of 20 questions left" for FREE, "Unlimited" for PREMIUM; the numbers are always the server's. */
export function questionsLabel(entry: Pick<UsageEntry, 'limit' | 'remaining' | 'limitReached'>): UsageLabel {
  if (entry.limit === null || entry.remaining === null) return { text: 'Unlimited questions', tone: 'normal' };
  if (entry.limitReached || entry.remaining <= 0) return { text: `No questions left of ${entry.limit}`, tone: 'full' };
  const text = `${entry.remaining} of ${entry.limit} questions left`;
  return { text, tone: entry.remaining <= LOW_QUESTIONS_LEFT ? 'warn' : 'normal' };
}

/**
 * How the provider that answered is described: by its display name, which the server resolves from its configuration (the
 * chain is configurable, so no provider is named here). The id stands in when a name is missing; null when there is neither.
 */
export function providerNote(providerName: string | null | undefined, provider?: string | null): string | null {
  const label = (providerName ?? '').trim() || (provider ?? '').trim();
  return label ? `Answered by ${label}` : null;
}
