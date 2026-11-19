import { describe, expect, it } from 'vitest';
import {
  counterVisible,
  isSendable,
  parseBlocks,
  parseInline,
  providerNote,
  allowanceNote,
  questionsLabel,
  retrySeconds,
  safeHref,
  sourceLabel,
  sourceLocation,
  splitCitations,
} from './askAi';

const refs = (...n: number[]) => new Set(n);

describe('splitCitations', () => {
  it('turns markers with a source into citations and leaves the rest as text', () => {
    expect(splitCitations('A [1] and B [99] and xs[0].', refs(1, 2))).toEqual([
      { kind: 'text', value: 'A ' },
      { kind: 'cite', ref: 1 },
      { kind: 'text', value: ' and B [99] and xs[0].' },
    ]);
  });

  it('handles adjacent markers and a marker at either end', () => {
    expect(splitCitations('[1][2]', refs(1, 2))).toEqual([{ kind: 'cite', ref: 1 }, { kind: 'cite', ref: 2 }]);
    expect(splitCitations('x[2]', refs(2))).toEqual([{ kind: 'text', value: 'x' }, { kind: 'cite', ref: 2 }]);
  });

  it('never makes a citation without sources and keeps plain text whole', () => {
    expect(splitCitations('only [1] here', refs())).toEqual([{ kind: 'text', value: 'only [1] here' }]);
    expect(splitCitations('', refs(1))).toEqual([]);
  });
});

describe('parseInline', () => {
  it('finds inline code, bold and safe links', () => {
    expect(parseInline('Use `login()` for **auth** see [docs](https://example.com/a).')).toEqual([
      { kind: 'text', value: 'Use ' },
      { kind: 'code', value: 'login()' },
      { kind: 'text', value: ' for ' },
      { kind: 'strong', value: 'auth' },
      { kind: 'text', value: ' see ' },
      { kind: 'link', href: 'https://example.com/a', value: 'docs' },
      { kind: 'text', value: '.' },
    ]);
  });

  it('drops the URL of a link that is not http(s) and keeps its text', () => {
    for (const url of ['javascript:alert(1)', 'data:text/html,<script>x</script>', 'vbscript:x', '/relative', '//evil.example']) {
      const out = parseInline(`[click me](${url})`);
      expect(out.some((part) => part.kind === 'link'), url).toBe(false); // never a link
      expect(out[0], url).toEqual({ kind: 'text', value: 'click me' });
      expect(out.every((part) => part.kind === 'text'), url).toBe(true); // whatever is left is inert text
    }
  });

  it('keeps hostile HTML as inert text', () => {
    const out = parseInline('<script>alert(1)</script> <img src=x onerror=alert(1)> **b**');
    expect(out.every((part) => part.kind === 'text' || part.kind === 'strong')).toBe(true);
    expect(out[0]).toEqual({ kind: 'text', value: '<script>alert(1)</script> <img src=x onerror=alert(1)> ' });
  });
});

describe('safeHref', () => {
  it('accepts only http and https', () => {
    expect(safeHref('https://example.com')).toBe('https://example.com/');
    expect(safeHref('http://example.com/x')).toBe('http://example.com/x');
    expect(safeHref('javascript:alert(1)')).toBeNull();
    expect(safeHref('not a url')).toBeNull();
  });
});

describe('parseBlocks', () => {
  it('parses paragraphs, lists and fenced code', () => {
    const blocks = parseBlocks('First line\nsecond line\n\n- one\n- two\n\n1. a\n2. b\n\n```java\nint x = 1;\n```\nAfter.');
    expect(blocks).toEqual([
      { kind: 'paragraph', inlines: [{ kind: 'text', value: 'First line second line' }] },
      { kind: 'list', ordered: false, items: [[{ kind: 'text', value: 'one' }], [{ kind: 'text', value: 'two' }]] },
      { kind: 'list', ordered: true, items: [[{ kind: 'text', value: 'a' }], [{ kind: 'text', value: 'b' }]] },
      { kind: 'code', language: 'java', value: 'int x = 1;' },
      { kind: 'paragraph', inlines: [{ kind: 'text', value: 'After.' }] },
    ]);
  });

  it('keeps a code block whole, markers and all, and closes an unclosed fence at the end', () => {
    expect(parseBlocks('```\na[1] = <b>x</b>\nmore')).toEqual([{ kind: 'code', language: '', value: 'a[1] = <b>x</b>\nmore' }]);
  });

  it('shows a heading as a bold paragraph and never produces an HTML block', () => {
    const blocks = parseBlocks('## Title\n<div onclick="x()">hi</div>');
    expect(blocks[0]).toEqual({ kind: 'paragraph', inlines: [{ kind: 'strong', value: 'Title' }] });
    expect(blocks.map((b) => b.kind).every((k) => ['paragraph', 'list', 'code'].includes(k))).toBe(true);
    expect(JSON.stringify(blocks[1])).toContain('<div onclick=');
  });

  it('copes with empty input and mixed line endings', () => {
    expect(parseBlocks('')).toEqual([]);
    expect(parseBlocks('a\r\n\r\nb')).toHaveLength(2);
  });
});

describe('sources', () => {
  it('formats the location with an en dash for a range', () => {
    expect(sourceLocation({ file: 'src/A.java', line: 41, endLine: 77 })).toBe('src/A.java:41–77');
    expect(sourceLocation({ file: 'src/A.java', line: 41, endLine: 41 })).toBe('src/A.java:41');
    expect(sourceLocation({ file: 'src/A.java', line: null, endLine: null })).toBe('src/A.java');
    expect(sourceLocation({ file: null, line: 1, endLine: 2 })).toBeNull();
  });

  it('names a chip by number, type and name', () => {
    expect(sourceLabel({ ref: 2, type: 'METHOD', name: 'login' })).toBe('Source 2: method login');
  });
});

describe('retrySeconds', () => {
  it('uses Retry-After within bounds and a default otherwise', () => {
    expect(retrySeconds(12)).toBe(12);
    expect(retrySeconds(0.2)).toBe(1);
    expect(retrySeconds(100000)).toBe(300);
    expect(retrySeconds(undefined)).toBe(30);
    expect(retrySeconds(-5)).toBe(30);
    expect(retrySeconds(Number.NaN)).toBe(30);
  });
});

describe('questionsLabel', () => {
  it('reads the server numbers and warns when few are left', () => {
    expect(questionsLabel({ limit: 15, remaining: 13, limitReached: false })).toEqual({
      text: '13 of 15 questions left this week',
      tone: 'normal',
      allowance: 'The free plan includes 15 free questions per week, and they reset every Monday.',
    });
    expect(questionsLabel({ limit: 15, remaining: 3, limitReached: false }).tone).toBe('warn');
    expect(questionsLabel({ limit: 15, remaining: 0, limitReached: true })).toMatchObject({ text: 'No questions left this week', tone: 'full' });
    expect(questionsLabel({ limit: null, remaining: null, limitReached: false })).toEqual({ text: 'Unlimited questions', tone: 'normal', allowance: null });
  });

  it('never invents a limit: the number in every sentence is the entry\'s', () => {
    expect(questionsLabel({ limit: 5, remaining: 4, limitReached: false }).text).toBe('4 of 5 questions left this week');
    expect(questionsLabel({ limit: 5, remaining: 4, limitReached: false }).allowance).toContain('5 free questions per week');
    expect(questionsLabel({ limit: 1, remaining: 1, limitReached: false }).allowance).toContain('1 free question per week');
    expect(allowanceNote(40)).toContain('40 free questions per week');
  });
});

describe('composer limits', () => {
  it('allows 1 to 2000 characters after trimming and shows the counter near the limit', () => {
    expect(isSendable('   ')).toBe(false);
    expect(isSendable('x')).toBe(true);
    expect(isSendable('x'.repeat(2000))).toBe(true);
    expect(isSendable('x'.repeat(2001))).toBe(false);
    expect(counterVisible(1799)).toBe(false);
    expect(counterVisible(1800)).toBe(true);
  });

  it('describes the provider that answered by the display name the server sent', () => {
    expect(providerNote('Gemini', 'gemini')).toBe('Answered by Gemini');
    expect(providerNote('OVHcloud AI Endpoints', 'ovh')).toBe('Answered by OVHcloud AI Endpoints');
    expect(providerNote('  Cloudflare Workers AI ', 'cloudflare')).toBe('Answered by Cloudflare Workers AI');
  });

  it('falls back to the id, and says nothing when there is neither', () => {
    expect(providerNote(undefined, 'some-provider')).toBe('Answered by some-provider');
    expect(providerNote('', 'some-provider')).toBe('Answered by some-provider');
    expect(providerNote(null, null)).toBeNull();
    expect(providerNote('   ', undefined)).toBeNull();
  });

  it('never names a provider itself: an unknown one is shown as the server named it', () => {
    expect(providerNote('A Provider Added Next Year', 'next-year')).toBe('Answered by A Provider Added Next Year');
    expect(providerNote('Z AI <beta>', 'zai')).toBe('Answered by Z AI <beta>'); // returned as plain text: React escapes it when rendering
  });
});
