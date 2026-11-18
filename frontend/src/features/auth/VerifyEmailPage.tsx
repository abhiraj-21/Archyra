import { useEffect, useState } from 'react';
import { Link } from 'react-router';
import { ButtonLink } from '@/components/ui/Button';
import { TextField } from '@/components/ui/Field';
import { Notice } from '@/components/ui/Notice';
import { Spinner } from '@/components/ui/Spinner';
import { hasCode } from '@/api/errors';
import { verifyEmailOnce } from '@/hooks/useAuthFlows';
import { describeError } from '@/lib/errorCopy';
import { AuthLayout } from './AuthLayout';
import { ResendVerification } from './ResendVerification';
import { emailError } from './validation';
import { useField } from './useField';

type State = { kind: 'working' } | { kind: 'done' } | { kind: 'expired' } | { kind: 'failed'; error: unknown };

export function VerifyEmailPage() {
  const [token] = useState(() => new URLSearchParams(window.location.search).get('token'));
  const [state, setState] = useState<State>(token ? { kind: 'working' } : { kind: 'expired' });
  const email = useField(emailError);

  useEffect(() => {
    // Drop the token from the address bar before anything else happens.
    if (window.location.search) window.history.replaceState(window.history.state, '', window.location.pathname);
    if (!token) return;
    let alive = true;
    verifyEmailOnce(token).then(
      () => alive && setState({ kind: 'done' }),
      (error: unknown) => alive && setState(hasCode(error, 'INVALID_OR_EXPIRED_TOKEN', 'VALIDATION_ERROR') ? { kind: 'expired' } : { kind: 'failed', error }),
    );
    return () => {
      alive = false;
    };
  }, [token]);

  if (state.kind === 'working') {
    return (
      <AuthLayout title="Verifying your email" noReferrer>
        <p className="flex items-center gap-sm text-muted" role="status">
          <Spinner size={18} /> Checking the link.
        </p>
      </AuthLayout>
    );
  }
  if (state.kind === 'done') {
    return (
      <AuthLayout title="Email verified" intro="Your address is confirmed. You can sign in now." noReferrer>
        <div>
          <ButtonLink to="/login" variant="primary" size="lg">
            Sign in
          </ButtonLink>
        </div>
      </AuthLayout>
    );
  }
  const copy = state.kind === 'failed' ? describeError(state.error) : null;
  return (
    <AuthLayout
      title={copy ? copy.title : 'This link has expired'}
      intro={copy ? copy.message : 'Verification links work once and expire after 24 hours. Send yourself a new one.'}
      noReferrer
      footer={
        <Link className="link" to="/login">
          Back to sign in
        </Link>
      }
    >
      {copy ? <Notice tone="error">{copy.message}</Notice> : null}
      <div className="grid gap-sm">
        <TextField label="Email" type="email" autoComplete="email" error={email.error} {...email.inputProps} />
        <ResendVerification email={email.isValid() ? email.value.trim() : ''} />
      </div>
    </AuthLayout>
  );
}
