import { describe, expect, it } from 'vitest';
import { describeLinkError, LINK_ERROR_FALLBACK } from './errorCopy';
import { isLastSignInMethod, isLinkAuthorizationUrl, parseLinkReturn, withoutLinkReturn } from './linkReturn';

const q = (s: string) => new URLSearchParams(s);

describe('parseLinkReturn', () => {
  it('reads a successful link', () => {
    expect(parseLinkReturn(q('link=ok&provider=google'))).toEqual({ kind: 'success', provider: 'google' });
    expect(parseLinkReturn(q('provider=github&link=ok'))).toEqual({ kind: 'success', provider: 'github' });
  });

  it('reads a link error code', () => {
    expect(parseLinkReturn(q('link_error=identity_already_linked'))).toEqual({ kind: 'error', code: 'identity_already_linked' });
  });

  it('ignores a success without a known provider, and unrelated queries', () => {
    expect(parseLinkReturn(q('link=ok'))).toBeNull();
    expect(parseLinkReturn(q('link=ok&provider=facebook'))).toBeNull();
    expect(parseLinkReturn(q('link=yes&provider=google'))).toBeNull();
    expect(parseLinkReturn(q('tab=plan'))).toBeNull();
  });

  it('an error wins over a success if both are present', () => {
    expect(parseLinkReturn(q('link=ok&provider=google&link_error=link_expired'))).toEqual({ kind: 'error', code: 'link_expired' });
  });
});

describe('withoutLinkReturn', () => {
  it('removes only the link parameters', () => {
    expect(withoutLinkReturn(q('link=ok&provider=google&tab=plan')).toString()).toBe('tab=plan');
    expect(withoutLinkReturn(q('link_error=x')).toString()).toBe('');
  });
});

describe('describeLinkError', () => {
  it.each(['oauth_cancelled', 'oauth_failed', 'oauth_account_disabled', 'identity_already_linked', 'provider_already_linked', 'link_expired'])(
    'has fixed copy for %s',
    (code) => {
      expect(describeLinkError(code)).not.toBe(LINK_ERROR_FALLBACK);
    },
  );

  it('never renders an unknown or hostile value', () => {
    expect(describeLinkError('<script>alert(1)</script>')).toBe(LINK_ERROR_FALLBACK);
    expect(describeLinkError('toString')).toBe(LINK_ERROR_FALLBACK);
    expect(describeLinkError('__proto__')).toBe(LINK_ERROR_FALLBACK);
  });

  it('does not name another account', () => {
    expect(describeLinkError('identity_already_linked')).not.toMatch(/@|owner|user /i);
  });
});

describe('isLinkAuthorizationUrl', () => {
  it('accepts only the same-origin start path for that provider', () => {
    expect(isLinkAuthorizationUrl('/oauth2/authorization/google?link_intent=abc_DEF-123', 'google')).toBe(true);
    expect(isLinkAuthorizationUrl('/oauth2/authorization/github?link_intent=abc', 'google')).toBe(false);
    expect(isLinkAuthorizationUrl('https://evil.example/oauth2/authorization/google?link_intent=abc', 'google')).toBe(false);
    expect(isLinkAuthorizationUrl('//evil.example/oauth2/authorization/google?link_intent=abc', 'google')).toBe(false);
    expect(isLinkAuthorizationUrl('/oauth2/authorization/google', 'google')).toBe(false);
    expect(isLinkAuthorizationUrl('/oauth2/authorization/google?link_intent=a b', 'google')).toBe(false);
  });
});

describe('isLastSignInMethod', () => {
  it('is true only without a password and with at most one identity', () => {
    expect(isLastSignInMethod(false, 1)).toBe(true);
    expect(isLastSignInMethod(false, 0)).toBe(true);
    expect(isLastSignInMethod(false, 2)).toBe(false);
    expect(isLastSignInMethod(true, 1)).toBe(false);
    expect(isLastSignInMethod(true, 0)).toBe(false);
  });
});
