import { useEffect, useId, useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { Link2, Unlink } from 'lucide-react';
import type { LinkedIdentity, LinkProvider } from '@/api/types';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Notice } from '@/components/ui/Notice';
import { Skeleton } from '@/components/ui/Skeleton';
import { useToast } from '@/components/ui/toastContext';
import { GitHubMark, GoogleMark } from '@/features/auth/ProviderMarks';
import { useLinkedIdentities, useRefreshIdentities, useStartLink, useUnlinkIdentity } from '@/hooks/useLinkedIdentities';
import { describeError, describeLinkError } from '@/lib/errorCopy';
import { formatDate } from '@/lib/format';
import { isLastSignInMethod, isLinkAuthorizationUrl, LINK_PROVIDER_LABEL, parseLinkReturn, withoutLinkReturn } from '@/lib/linkReturn';

const PROVIDERS: readonly LinkProvider[] = ['google', 'github'];

function Mark({ provider }: { provider: LinkProvider }) {
  return provider === 'google' ? <GoogleMark size={20} /> : <GitHubMark size={20} />;
}

/** What identifies the linked account: the GitHub handle, or the provider's email; an em dash when it gave neither. */
function identityDetail(provider: LinkProvider, identity: LinkedIdentity): string {
  if (provider === 'github' && identity.login) return `@${identity.login}`;
  return identity.email ?? identity.login ?? '—';
}

/**
 * Settings → Sign-in methods: link or unlink Google and GitHub. Linking is a full page navigation to the provider
 * (the backend issues a one-time intent first) and the provider sends the browser back to
 * `/settings?link=ok&provider=…` or `/settings?link_error=…`, which this section reads once and then removes.
 */
export function SignInMethods() {
  const identities = useLinkedIdentities();
  const startLink = useStartLink();
  const unlink = useUnlinkIdentity();
  const refreshIdentities = useRefreshIdentities();
  const { showToast } = useToast();
  const location = useLocation();
  const navigate = useNavigate();

  const [banner, setBanner] = useState<string | null>(null);
  const [linking, setLinking] = useState<LinkProvider | null>(null);
  const [linkError, setLinkError] = useState<{ provider: LinkProvider; text: string } | null>(null);
  const [confirming, setConfirming] = useState<LinkProvider | null>(null);
  const helpId = useId();

  // Read the return parameters once (a ref survives StrictMode's second effect), then replace the URL without them.
  const handled = useRef<string | null>(null);
  useEffect(() => {
    if (handled.current === location.search) return;
    const params = new URLSearchParams(location.search);
    const result = parseLinkReturn(params);
    if (!result) return;
    handled.current = location.search;
    if (result.kind === 'success') {
      setBanner(null);
      showToast(`${LINK_PROVIDER_LABEL[result.provider]} is now linked to your account.`);
    } else {
      setBanner(describeLinkError(result.code));
    }
    void refreshIdentities();
    const rest = withoutLinkReturn(params).toString();
    navigate({ pathname: location.pathname, search: rest ? `?${rest}` : '' }, { replace: true });
  }, [location.pathname, location.search, navigate, refreshIdentities, showToast]);

  const data = identities.data;
  const byProvider = new Map(data?.identities.map((i) => [i.provider, i] as const));
  const lastMethod = data ? isLastSignInMethod(data.hasPassword, data.identities.length) : false;

  async function onLink(provider: LinkProvider) {
    setLinkError(null);
    setBanner(null);
    setLinking(provider);
    try {
      const { authorizationUrl } = await startLink.mutateAsync(provider);
      if (!isLinkAuthorizationUrl(authorizationUrl, provider)) throw new Error('unexpected authorization url');
      window.location.assign(authorizationUrl); // leaves the page: the loading state stays until it does
    } catch (error) {
      setLinking(null);
      const copy = describeError(error);
      setLinkError({ provider, text: copy.code === 'RATE_LIMITED' ? 'Too many link attempts. Wait a moment, then try again.' : copy.message });
      if (copy.code === 'PROVIDER_ALREADY_LINKED') void refreshIdentities();
    }
  }

  const unlinkError = unlink.error ? describeError(unlink.error) : null;

  return (
    <div className="grid gap-md">
      {banner ? (
        <Notice
          tone="error"
          title="Could not link that account"
          action={
            <Button size="sm" onClick={() => setBanner(null)}>
              Dismiss
            </Button>
          }
        >
          {banner}
        </Notice>
      ) : null}

      {identities.isPending ? <Skeleton className="h-[7rem]" /> : null}
      {identities.isError ? (
        <Notice
          tone="error"
          title="Sign-in methods could not be loaded"
          action={
            <Button size="sm" onClick={() => void identities.refetch()}>
              Try again
            </Button>
          }
        >
          {describeError(identities.error).message}
        </Notice>
      ) : null}

      {data ? (
        <ul className="grid gap-xs" aria-label="Sign-in methods">
          {PROVIDERS.map((provider) => {
            const identity = byProvider.get(provider);
            const label = LINK_PROVIDER_LABEL[provider];
            const rowError = linkError?.provider === provider ? linkError.text : null;
            return (
              <li key={provider} className="grid gap-xs rounded-card border border-rule-2 bg-cotton p-md">
                <div className="flex flex-wrap items-center gap-sm">
                  <span className="text-ink" aria-hidden="true">
                    <Mark provider={provider} />
                  </span>
                  <div className="grid min-w-0 flex-1 gap-3xs">
                    <div className="flex flex-wrap items-center gap-xs">
                      <span className="font-medium text-ink">{label}</span>
                      <span className="chip" data-tone={identity ? 'success' : 'quiet'}>
                        {identity ? 'Linked' : 'Not linked'}
                      </span>
                    </div>
                    {identity ? (
                      <p className="break-words text-sm text-muted">
                        <span className="sr-only">Account: </span>
                        {identityDetail(provider, identity)}
                        <span aria-hidden="true"> · </span>
                        <span className="sr-only">Linked on: </span>
                        {formatDate(identity.linkedAt) || '—'}
                      </p>
                    ) : (
                      <p className="text-sm text-muted">Sign in with {label} as well as with your other methods.</p>
                    )}
                  </div>
                  {identity ? (
                    <Button
                      icon={<Unlink size={16} aria-hidden="true" />}
                      disabled={lastMethod}
                      aria-describedby={lastMethod ? helpId : undefined}
                      onClick={() => setConfirming(provider)}
                    >
                      Unlink
                    </Button>
                  ) : (
                    <Button
                      variant="primary"
                      icon={<Link2 size={16} aria-hidden="true" />}
                      loading={linking === provider}
                      disabled={linking !== null && linking !== provider}
                      onClick={() => void onLink(provider)}
                    >
                      Link
                    </Button>
                  )}
                </div>
                {rowError ? (
                  <Notice tone="error">{rowError}</Notice>
                ) : null}
              </li>
            );
          })}
        </ul>
      ) : null}

      {data && lastMethod ? (
        <p id={helpId} className="text-sm text-muted">
          This is your only way to sign in, so it cannot be removed. Set a password or link another provider first.
          {!data.hasPassword ? (
            <>
              {' '}
              To set a password, use <Link to="/forgot-password" className="link">Forgot password</Link> with your account email.
            </>
          ) : null}
        </p>
      ) : null}

      {unlinkError ? (
        <Notice tone="error" title={unlinkError.title}>
          {unlinkError.message}
        </Notice>
      ) : null}

      <Dialog
        open={confirming !== null}
        onClose={() => setConfirming(null)}
        title={confirming ? `Unlink ${LINK_PROVIDER_LABEL[confirming]}?` : 'Unlink'}
        footer={
          <>
            <Button onClick={() => setConfirming(null)}>Cancel</Button>
            <Button
              variant="danger"
              loading={unlink.isPending}
              onClick={() => {
                if (!confirming) return;
                unlink.mutate(confirming, { onSettled: () => setConfirming(null) });
              }}
            >
              Unlink
            </Button>
          </>
        }
      >
        <p>
          You will no longer be able to sign in with {confirming ? LINK_PROVIDER_LABEL[confirming] : 'it'}. Your account, analyses and other
          sign-in methods stay as they are. We will email you to confirm the change.
        </p>
      </Dialog>
    </div>
  );
}
