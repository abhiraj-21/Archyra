import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ensureCsrf, logoutSession, refreshSession } from '@/api/auth';
import { login as loginRequest } from '@/api/endpoints/auth';
import { getMe } from '@/api/endpoints/users';
import { queryKeys } from '@/api/queryKeys';
import { onSessionExpired } from '@/api/session';
import { canSeeAdmin } from '@/lib/roles';
import { SessionContext, type SessionStatus, type SessionValue } from './sessionContext';

/**
 * Session lifecycle. Boot: csrf → refresh (the HttpOnly cookie) → GET /users/me → render. Until that settles the
 * app shows the boot screen, so nobody sees a signed-in screen flash for an anonymous user or the reverse.
 */
// One boot per page load, shared by StrictMode's double effects: two refreshes would replay the rotating cookie.
let boot: Promise<boolean> | null = null;
function bootOnce(): Promise<boolean> {
  if (!boot) {
    boot = (async () => {
      await ensureCsrf();
      return refreshSession();
    })();
    boot.catch(() => {
      boot = null; // a network failure may be retried
    });
  }
  return boot;
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<SessionStatus>('booting');
  const [attempt, setAttempt] = useState(0);
  const [endReason, setEndReason] = useState<'signed-out' | 'expired' | null>(null);

  useEffect(() => {
    let alive = true;
    bootOnce()
      .then((ok) => alive && setStatus(ok ? 'authenticated' : 'anonymous'))
      .catch(() => alive && setStatus('unreachable'));
    return () => {
      alive = false;
    };
  }, [attempt]);

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
      queryClient.clear();
      setEndReason(null);
      await queryClient.fetchQuery({ queryKey: queryKeys.me, queryFn: ({ signal }) => getMe(signal) });
      setStatus('authenticated');
    },
    [queryClient],
  );

  const completeExternalSignIn = useCallback(async () => {
    await ensureCsrf();
    const ok = await refreshSession();
    if (!ok) {
      setStatus('anonymous');
      return false;
    }
    queryClient.clear();
    setEndReason(null);
    await queryClient.fetchQuery({ queryKey: queryKeys.me, queryFn: ({ signal }) => getMe(signal) });
    setStatus('authenticated');
    return true;
  }, [queryClient]);

  const signOut = useCallback(async () => {
    await logoutSession();
    queryClient.clear();
    setEndReason('signed-out');
    setStatus('anonymous');
  }, [queryClient]);

  const retryBoot = useCallback(() => {
    setStatus('booting');
    setAttempt((n) => n + 1);
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
      completeExternalSignIn,
      signOut,
      retryBoot,
      refetchMe: meQuery.refetch,
    }),
    [status, endReason, me, meState, signIn, completeExternalSignIn, signOut, retryBoot, meQuery.refetch],
  );

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}
