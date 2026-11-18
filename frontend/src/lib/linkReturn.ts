/**
 * Where a link started in Settings comes back: `/settings?link=ok&provider=<p>` or `/settings?link_error=<code>`.
 * Only fixed values are accepted; the raw query text is never rendered. Anything else is ignored (or, for an
 * unknown error code, becomes the generic failure).
 */
import type { LinkProvider } from '@/api/types';

export const LINK_PROVIDER_LABEL: Record<LinkProvider, string> = { google: 'Google', github: 'GitHub' };

export type LinkReturn = { kind: 'success'; provider: LinkProvider } | { kind: 'error'; code: string };

export function isLinkProvider(value: string | null | undefined): value is LinkProvider {
  return value === 'google' || value === 'github';
}

export function parseLinkReturn(params: URLSearchParams): LinkReturn | null {
  const error = params.get('link_error');
  if (error !== null) return { kind: 'error', code: error };
  if (params.get('link') === 'ok') {
    const provider = params.get('provider');
    if (isLinkProvider(provider)) return { kind: 'success', provider };
  }
  return null;
}

/** The same query with the link-return parameters removed (anything else is kept). */
export function withoutLinkReturn(params: URLSearchParams): URLSearchParams {
  const next = new URLSearchParams(params);
  next.delete('link');
  next.delete('provider');
  next.delete('link_error');
  return next;
}

/** Only a same-origin OAuth start path may be navigated to; the server's value is checked, not trusted. */
export function isLinkAuthorizationUrl(url: string, provider: LinkProvider): boolean {
  return url.startsWith(`/oauth2/authorization/${provider}?link_intent=`) && !/[\s\\]/.test(url);
}

/** The account's last way to sign in cannot be removed: no password and only one identity. */
export function isLastSignInMethod(hasPassword: boolean, identityCount: number): boolean {
  return !hasPassword && identityCount <= 1;
}
