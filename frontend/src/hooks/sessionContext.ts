import { createContext, useContext } from 'react';
import type { UserProfile } from '@/api/types';

export type SessionStatus = 'booting' | 'anonymous' | 'authenticated' | 'unreachable';

export interface SessionValue {
  status: SessionStatus;
  /** Why the last session ended: a deliberate sign-out goes to a plain /login, an expiry remembers the page, a deleted account goes to the landing page. */
  endReason: 'signed-out' | 'expired' | 'deleted' | null;
  /** The current user once /users/me has loaded. */
  me: UserProfile | undefined;
  /** /users/me is loading or failed while the session itself is valid. */
  meState: 'loading' | 'error' | 'ready' | 'none';
  isAdmin: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
  /**
   * The account was just deleted on the server (its refresh cookie is already cleared and its token family gone): end
   * the local session the way a sign-out does, without calling the server again. Synchronous, so the caller can navigate
   * in the same tick.
   */
  endAfterDeletion: () => void;
  retryBoot: () => void;
  /** Gives up waiting for the boot (retry screen's "Back to sign in"): the visitor is treated as anonymous. */
  abandonBoot: () => void;
  refetchMe: () => Promise<unknown>;
}

export const SessionContext = createContext<SessionValue | null>(null);

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession must be used inside SessionProvider');
  return value;
}
