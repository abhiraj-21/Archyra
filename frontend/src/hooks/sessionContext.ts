import { createContext, useContext } from 'react';
import type { UserProfile } from '@/api/types';

export type SessionStatus = 'booting' | 'anonymous' | 'authenticated' | 'unreachable';

export interface SessionValue {
  status: SessionStatus;
  /** Why the last session ended: a deliberate sign-out goes to a plain /login, an expiry remembers the page. */
  endReason: 'signed-out' | 'expired' | null;
  /** The current user once /users/me has loaded. */
  me: UserProfile | undefined;
  /** /users/me is loading or failed while the session itself is valid. */
  meState: 'loading' | 'error' | 'ready' | 'none';
  isAdmin: boolean;
  signIn: (email: string, password: string) => Promise<void>;
  /** After an OAuth redirect lands on /auth/callback: csrf → refresh → /users/me. */
  completeExternalSignIn: () => Promise<boolean>;
  signOut: () => Promise<void>;
  retryBoot: () => void;
  refetchMe: () => Promise<unknown>;
}

export const SessionContext = createContext<SessionValue | null>(null);

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error('useSession must be used inside SessionProvider');
  return value;
}
