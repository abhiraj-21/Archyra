import { describe, expect, it } from 'vitest';
import { repoLabel, safeGithubHref, validateRepoUrl } from './url';

describe('validateRepoUrl', () => {
  it.each([
    ['https://github.com/spring-projects/spring-petclinic', 'https://github.com/spring-projects/spring-petclinic'],
    ['  https://github.com/spring-projects/spring-petclinic/  ', 'https://github.com/spring-projects/spring-petclinic'],
    ['https://github.com/spring-projects/spring-petclinic.git', 'https://github.com/spring-projects/spring-petclinic'],
    ['https://github.com/spring-projects/spring-petclinic.git/', 'https://github.com/spring-projects/spring-petclinic'],
    ['https://www.github.com/a/b', 'https://github.com/a/b'],
    ['https://github.com/a-b/c_d.e', 'https://github.com/a-b/c_d.e'],
  ])('accepts %s', (input, expected) => {
    const result = validateRepoUrl(input);
    expect(result).toMatchObject({ ok: true, url: expected });
  });

  it.each([
    ['', 'EMPTY'],
    ['not a url at all', 'NOT_A_URL'],
    ['https://gitlab.com/a/b', 'OTHER_HOST'],
    ['https://github.com/a', 'MISSING_REPO'],
    ['https://user:token@github.com/a/b', 'CREDENTIALS'],
    ['https://github.com/a/b?tab=readme', 'QUERY_OR_FRAGMENT'],
    ['https://github.com/a/b#readme', 'QUERY_OR_FRAGMENT'],
    ['https://github.com/a/b/tree/main/src', 'EXTRA_PATH'],
    ['http://github.com/a/b', 'INSECURE'],
    ['github.com/a/b', 'NO_SCHEME'],
    ['git@github.com:a/b.git', 'SSH'],
    ['https://github.com/a/..', 'BAD_CHARACTERS'],
    ['https://github.com/a_b/c', 'BAD_CHARACTERS'],
    ['file:///etc/passwd', 'OTHER_HOST'],
  ])('rejects %s as %s', (input, reason) => {
    expect(validateRepoUrl(input)).toMatchObject({ ok: false, reason });
  });

  it('suggests the canonical address for fixable mistakes', () => {
    expect(validateRepoUrl('github.com/a/b')).toMatchObject({ suggestion: 'https://github.com/a/b' });
    expect(validateRepoUrl('git@github.com:a/b.git')).toMatchObject({ suggestion: 'https://github.com/a/b' });
    expect(validateRepoUrl('https://github.com/a/b/blob/main/README.md')).toMatchObject({ suggestion: 'https://github.com/a/b' });
  });

  it('rejects URLs over 200 characters', () => {
    expect(validateRepoUrl(`https://github.com/a/${'r'.repeat(200)}`)).toMatchObject({ ok: false, reason: 'TOO_LONG' });
  });
});

describe('repoLabel and safeGithubHref', () => {
  it('labels a GitHub URL as owner/repo', () => {
    expect(repoLabel('https://github.com/a/b')).toBe('a/b');
  });

  it('only links validated GitHub URLs', () => {
    expect(safeGithubHref('https://github.com/a/b')).toBe('https://github.com/a/b');
    expect(safeGithubHref('javascript:alert(1)')).toBeNull();
    expect(safeGithubHref('https://evil.example/a/b')).toBeNull();
  });
});
