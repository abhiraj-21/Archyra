import { useEffect } from 'react';
import { hasCode } from '@/api/errors';
import { useSession } from '@/hooks/sessionContext';

/**
 * If an admin call answers 403 ACCESS_DENIED (the role changed since /users/me loaded), refetch /users/me. The
 * admin guard then sees a non-admin and renders the ordinary 404 screen.
 */
export function useAdminDenied(error: unknown): boolean {
  const { refetchMe } = useSession();
  const denied = hasCode(error, 'ACCESS_DENIED');
  useEffect(() => {
    if (denied) void refetchMe();
  }, [denied, refetchMe]);
  return denied;
}
