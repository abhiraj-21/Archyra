import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import { Spinner } from '@/components/ui/Spinner';
import { useSession } from '@/hooks/sessionContext';
import { takeReturnTo } from '@/lib/returnTo';
import { AuthLayout } from './AuthLayout';

/**
 * Google/GitHub land here after the backend set the refresh cookie (no token is in the URL). This page does not
 * hydrate anything: the session boot (csrf → refresh → /users/me) runs once per page load and `BootGate` holds the
 * route until it settles, so by the time this renders the session is either established or it is not. A timeout or
 * network failure never reaches here either: the boot gate shows its retry screen.
 */
export function OAuthCallbackPage() {
  const { status } = useSession();
  const navigate = useNavigate();

  useEffect(() => {
    if (status === 'authenticated') navigate(takeReturnTo(), { replace: true });
    // The provider login did not produce a session.
    else if (status === 'anonymous') navigate('/login?error=oauth_failed', { replace: true });
  }, [status, navigate]);

  return (
    <AuthLayout title="Signing you in">
      <p className="flex items-center gap-sm text-muted" role="status">
        <Spinner size={18} /> Finishing sign-in.
      </p>
    </AuthLayout>
  );
}
