import { describe, expect, it } from 'vitest';
import { dependenciesParam, dependentsParam, parseDependencies, MAX_QUERY_LENGTH, parseDependents, parseEdgeTypes, parseNodeTypes, parseQuery, queryParam } from './graphParams';

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

describe('"Include dependents" and "Include dependencies" in the URL', () => {
  it('is on when the parameter is missing, and off only for the exact value 0', () => {
    expect(parseDependents(null)).toBe(true);
    expect(parseDependents('0')).toBe(false);
    for (const garbled of ['', '1', 'true', 'false', 'off', '00', ' 0', 'no']) expect(parseDependents(garbled)).toBe(true);
  });

  it('writes 0 only when turned off, and nothing when on, so default URLs stay clean', () => {
    expect(dependentsParam(true)).toBeNull();
    expect(dependentsParam(false)).toBe('0');
  });

  it('the two switches are independent parameters with the same rules', () => {
    expect(parseDependencies(null)).toBe(true);
    expect(parseDependencies('0')).toBe(false);
    for (const garbled of ['', '1', 'true', 'false', ' 0', 'no']) expect(parseDependencies(garbled)).toBe(true);
    expect(dependenciesParam(true)).toBeNull();
    expect(dependenciesParam(false)).toBe('0');
    const params = new URLSearchParams('q=auth&dependencies=0');
    expect(parseDependents(params.get('dependents'))).toBe(true); // turning one off leaves the other on
    expect(parseDependencies(params.get('dependencies'))).toBe(false);
  });

  it('round-trips through the serializer and the parser, next to ?q=', () => {
    for (const on of [true, false]) {
      const params = new URLSearchParams();
      params.set('q', 'auth');
      const written = dependentsParam(on);
      if (written !== null) params.set('dependents', written);
      const read = new URLSearchParams(params.toString());
      expect(parseDependents(read.get('dependents'))).toBe(on);
      expect(parseQuery(read.get('q'))).toBe('auth');
      expect(read.has('dependents')).toBe(!on);
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
