import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '@/components/ui/Button';
import { Spinner } from '@/components/ui/Spinner';
import { useSession } from '@/hooks/sessionContext';
import { describeError } from '@/lib/errorCopy';
import { runOnce } from '@/lib/once';
import { takeReturnTo } from '@/lib/returnTo';
import { AuthLayout } from './AuthLayout';

/**
 * Google/GitHub land here after the backend set the refresh cookie. No token is in the URL: the SPA runs
 * csrf → refresh → /users/me exactly as after a password login, then goes to the remembered destination.
 */
export function OAuthCallbackPage() {
  const { completeExternalSignIn } = useSession();
  const navigate = useNavigate();
  const [error, setError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let alive = true;
    runOnce(`oauth-callback:${attempt}`, completeExternalSignIn).then(
      (ok) => {
        if (!alive) return;
        if (ok) navigate(takeReturnTo(), { replace: true });
        else navigate('/login?error=oauth_failed', { replace: true });
      },
      (e: unknown) => alive && setError(e),
    );
    return () => {
      alive = false;
    };
  }, [attempt, completeExternalSignIn, navigate]);

  if (error) {
    const copy = describeError(error);
    return (
      <AuthLayout title={copy.title} intro={copy.message}>
        <div>
          <Button variant="primary" onClick={() => { setError(null); setAttempt((n) => n + 1); }}>
            Try again
          </Button>
        </div>
      </AuthLayout>
    );
  }
  return (
    <AuthLayout title="Signing you in">
      <p className="flex items-center gap-sm text-muted" role="status">
        <Spinner size={18} /> Finishing sign-in.
      </p>
    </AuthLayout>
  );
}
