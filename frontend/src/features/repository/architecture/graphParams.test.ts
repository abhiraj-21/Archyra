import { describe, expect, it } from 'vitest';
import { MAX_QUERY_LENGTH, parseEdgeTypes, parseNodeTypes, parseQuery, queryParam } from './graphParams';

describe('the name filter in the URL', () => {
  it('reads ?q= trimmed, and a missing or blank one as no filter', () => {
    expect(parseQuery('service')).toBe('service');
    expect(parseQuery('  Service  ')).toBe('Service');
    expect(parseQuery('')).toBe('');
    expect(parseQuery('   ')).toBe('');
    expect(parseQuery(null)).toBe('');
  });

  it('keeps a term of exactly the longest length and drops one that is longer (it is not cut)', () => {
    expect(parseQuery('a'.repeat(MAX_QUERY_LENGTH))).toHaveLength(MAX_QUERY_LENGTH);
    expect(parseQuery('a'.repeat(MAX_QUERY_LENGTH + 1))).toBe('');
    expect(parseQuery(`  ${'a'.repeat(MAX_QUERY_LENGTH)}  `)).toHaveLength(MAX_QUERY_LENGTH);
  });

  it('writes nothing for an empty term, so default URLs stay clean, and the trimmed term otherwise', () => {
    expect(queryParam('')).toBeNull();
    expect(queryParam('   ')).toBeNull();
    expect(queryParam('  svc ')).toBe('svc');
    expect(queryParam('a'.repeat(MAX_QUERY_LENGTH + 1))).toBeNull();
  });

  it('round-trips through the serializer and the parser', () => {
    for (const term of ['service', 'Order Service', 'x', 'com.example', 'a&b=c', 'ünï', '100%']) {
      const params = new URLSearchParams();
      params.set('q', queryParam(term) as string);
      expect(parseQuery(new URLSearchParams(params.toString()).get('q'))).toBe(term);
    }
  });
});

describe('the type parameters are unchanged', () => {
  it('still parses nodes and edges', () => {
    expect(parseNodeTypes('CLASS,MODULE,NOPE')).toEqual(['CLASS', 'MODULE']);
    expect(parseNodeTypes('')).toEqual([]);
    expect(parseNodeTypes(null)).toBeNull();
    expect(parseEdgeTypes('DEPENDS_ON')).toEqual(['DEPENDS_ON']);
  });
});
