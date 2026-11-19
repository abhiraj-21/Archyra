import { Navigate, Outlet, useLocation, useNavigate } from 'react-router';
import { ACCOUNT_DELETED_STATE } from '@/hooks/accountDeletedState';
import { useSession } from '@/hooks/sessionContext';
import { safeNext } from '@/lib/returnTo';
import { BootScreen } from '@/features/system/BootScreen';
import { NotFoundPage } from '@/features/system/NotFoundPage';

/** Waits for the boot sequence everywhere, so no route renders for the wrong user. */
export function BootGate() {
  const { status, retryBoot, abandonBoot } = useSession();
  const navigate = useNavigate();
  if (status === 'booting') return <BootScreen />;
  if (status === 'unreachable') {
    return (
      <BootScreen
        failed
        onRetry={retryBoot}
        onBack={() => {
          abandonBoot();
          navigate('/login', { replace: true });
        }}
      />
    );
  }
  return <Outlet />;
}

/** Signed-in routes. Anonymous users go to /login with the destination remembered. */
export function RequireAuth() {
  const { status, endReason, meState, refetchMe } = useSession();
  const location = useLocation();
  if (status !== 'authenticated') {
    if (endReason === 'signed-out') return <Navigate to="/login" replace />;
    // The account was just deleted: the landing page, with the notice (the page the guard left is gone with the account).
    if (endReason === 'deleted') return <Navigate to="/" replace state={ACCOUNT_DELETED_STATE} />;
    const next = `${location.pathname}${location.search}`;
    return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />;
  }
  if (meState === 'loading') return <BootScreen />;
  if (meState === 'error') return <BootScreen failed message="Your account could not be loaded." onRetry={() => void refetchMe()} />;
  return <Outlet />;
}

/** /login and /register: a signed-in user goes straight on. */
export function RedirectIfAuthed() {
  const { status } = useSession();
  const location = useLocation();
  if (status === 'authenticated') {
    return <Navigate to={safeNext(new URLSearchParams(location.search).get('next'))} replace />;
  }
  return <Outlet />;
}

/**
 * /admin/**: rendered only when GET /users/me says role ADMIN. Everyone else gets the ordinary 404, identical to
 * an unknown route. This is a UX gate; the backend enforces admin access on every call.
 */
export function RequireAdmin() {
  const { status, isAdmin, meState } = useSession();
  // Anonymous visitors get the 404 too: a login redirect would reveal that the route exists.
  if (status !== 'authenticated') return <NotFoundPage />;
  if (meState === 'loading') return <BootScreen />;
  if (!isAdmin) return <NotFoundPage />;
  return <Outlet />;
}
