import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { abortAuthRequests, ensureCsrf, logoutSession, refreshSession } from '@/api/auth';
import { login as loginRequest } from '@/api/endpoints/auth';
import { getMe } from '@/api/endpoints/users';
import { isApiError } from '@/api/errors';
import { queryKeys } from '@/api/queryKeys';
import { clearAccessToken, onSessionExpired } from '@/api/session';
import type { UserProfile } from '@/api/types';
import { canSeeAdmin } from '@/lib/roles';
import { SessionContext, type SessionStatus, type SessionValue } from './sessionContext';

/**
 * Session lifecycle. Boot: csrf → refresh (the HttpOnly cookie) → GET /users/me → render. This is the ONLY code
 * that hydrates a session on page load; /auth/callback after a Google or GitHub login waits for it like every
 * other page and never calls refresh itself. Until it settles the app shows the boot screen, so nobody sees a
 * signed-in screen flash for an anonymous user or the reverse.
 */

/** The whole boot sequence gets this long; then the retry screen replaces the boot screen. Never spin forever. */
export const BOOT_TIMEOUT_MS = 10_000;

type BootResult = { me: UserProfile } | null; // null: no valid session (a normal outcome for a visitor)

class BootTimeoutError extends Error {
  constructor() {
    super('The session boot timed out.');
  }
}

async function runBoot(queryClient: QueryClient, signal: AbortSignal): Promise<BootResult> {
  await ensureCsrf();
  if (!(await refreshSession())) return null;
  try {
    const me = await getMe(signal);
    // Seed the cache so nothing mounts with a pending /users/me (and nobody has to clear the cache over observers).
    queryClient.setQueryData(queryKeys.me, me);
    return { me };
  } catch (error) {
    if (isApiError(error) && error.status === 401) return null;
    throw error;
  }
}

// One boot per page load, a module-level single flight: independent of component mounts, effect cleanup and abort
// signals, so StrictMode's double effects and any remount share it. Two refreshes would replay the rotating cookie.
let boot: Promise<BootResult> | null = null;

function bootOnce(queryClient: QueryClient): Promise<BootResult> {
  if (boot) return boot;
  const controller = new AbortController();
  const attempt = new Promise<BootResult>((resolve, reject) => {
    const timer = setTimeout(() => reject(new BootTimeoutError()), BOOT_TIMEOUT_MS);
    runBoot(queryClient, controller.signal).then(resolve, reject).finally(() => clearTimeout(timer));
  });
  boot = attempt;
  attempt.catch(() => {
    // A failure or timeout is not remembered: cancel what is still running so a late answer cannot rotate the
    // cookie under the retry, and let the next call start a fresh boot.
    controller.abort();
    abortAuthRequests();
    if (boot === attempt) boot = null;
  });
  return attempt;
}

/** Forgets a finished or failed boot so the next bootOnce is a fresh single flight. */
function resetBoot(): void {
  boot = null;
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<SessionStatus>('booting');
  const [attempt, setAttempt] = useState(0);
  const [endReason, setEndReason] = useState<'signed-out' | 'expired' | 'deleted' | null>(null);

  useEffect(() => {
    let alive = true;
    bootOnce(queryClient)
      .then((result) => alive && setStatus(result ? 'authenticated' : 'anonymous'))
      .catch(() => alive && setStatus('unreachable'));
    return () => {
      alive = false;
    };
  }, [attempt, queryClient]);

  useEffect(() => {
    onSessionExpired(() => {
      queryClient.clear();
      setEndReason('expired');
      setStatus('anonymous');
    });
    return () => onSessionExpired(null);
  }, [queryClient]);

  const meQuery = useQuery({
    queryKey: queryKeys.me,
    queryFn: ({ signal }) => getMe(signal),
    enabled: status === 'authenticated',
    staleTime: 60_000,
  });

  const signIn = useCallback(
    async (email: string, password: string) => {
      await loginRequest(email, password);
      const profile = await getMe();
      // Start the new session from an empty cache, then seed it with the new user's profile.
      queryClient.clear();
      queryClient.setQueryData(queryKeys.me, profile);
      setEndReason(null);
      setStatus('authenticated');
    },
    [queryClient],
  );

  const signOut = useCallback(async () => {
    await logoutSession();
    queryClient.clear();
    setEndReason('signed-out');
    setStatus('anonymous');
  }, [queryClient]);

  const endAfterDeletion = useCallback(() => {
    clearAccessToken();
    queryClient.clear();
    setEndReason('deleted'); // the guard sends the visitor to the landing page with the notice, not to /login
    setStatus('anonymous');
  }, [queryClient]);

  const retryBoot = useCallback(() => {
    resetBoot();
    setStatus('booting');
    setAttempt((n) => n + 1);
  }, []);

  /** "Back to sign in" from the retry screen: stop waiting for the server and treat the visitor as anonymous. */
  const abandonBoot = useCallback(() => {
    resetBoot();
    setEndReason('signed-out');
    setStatus('anonymous');
  }, []);

  const me = status === 'authenticated' ? meQuery.data : undefined;
  const meState: SessionValue['meState'] =
    status !== 'authenticated' ? 'none' : meQuery.data ? 'ready' : meQuery.isError ? 'error' : 'loading';

  const value = useMemo<SessionValue>(
    () => ({
      status,
      endReason,
      me,
      meState,
      isAdmin: canSeeAdmin(me),
      signIn,
      signOut,
      endAfterDeletion,
      retryBoot,
      abandonBoot,
      refetchMe: meQuery.refetch,
    }),
    [status, endReason, me, meState, signIn, signOut, endAfterDeletion, retryBoot, abandonBoot, meQuery.refetch],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
