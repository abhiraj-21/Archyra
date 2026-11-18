import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { Button } from '@/components/ui/Button';
import { useCountdown } from '@/components/ui/useCountdown';
import { PasswordField, TextField } from '@/components/ui/Field';
import { Notice } from '@/components/ui/Notice';
import { hasCode } from '@/api/errors';
import { useSession } from '@/hooks/sessionContext';
import { describeError, describeOAuthError } from '@/lib/errorCopy';
import { safeNext } from '@/lib/returnTo';
import { AuthLayout } from './AuthLayout';
import { ProviderButtons } from './ProviderButtons';
import { ResendVerification } from './ResendVerification';
import { emailError } from './validation';
import { useField } from './useField';

export function LoginPage() {
  const { signIn } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  const [params, setParams] = useSearchParams();
  const next = safeNext(params.get('next'));
  // Read the OAuth error once, then drop it from the address bar. The raw value is never rendered.
  const [oauthMessage] = useState(() => describeOAuthError(params.get('error')));
  const notice = (location.state as { notice?: string } | null)?.notice;

  useEffect(() => {
    if (params.has('error')) {
      const copy = new URLSearchParams(params);
      copy.delete('error');
      setParams(copy, { replace: true });
    }
  }, [params, setParams]);

  const email = useField(emailError);
  const password = useField((v) => (v ? null : 'Enter your password.'));
  const [error, setError] = useState<unknown>(null);
  const [pending, setPending] = useState(false);
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const retryLeft = useCountdown(hasCode(error, 'RATE_LIMITED') ? error.retryAfter : undefined);
  const blocked = hasCode(error, 'RATE_LIMITED') && retryLeft > 0;

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    email.touch();
    password.touch();
    if (!email.isValid()) return emailRef.current?.focus();
    if (!password.isValid()) return passwordRef.current?.focus();
    setPending(true);
    setError(null);
    try {
      await signIn(email.value.trim(), password.value);
      navigate(next, { replace: true });
    } catch (err) {
      setError(err);
      setPending(false);
    }
  }

  const copy = error ? describeError(error) : null;

  return (
    <AuthLayout
      title="Sign in to Archyra"
      footer={
        <>
          New to Archyra?{' '}
          <Link className="link" to={`/register${params.get('next') ? `?next=${encodeURIComponent(next)}` : ''}`}>
            Create an account
          </Link>
        </>
      }
    >
      {notice ? <Notice tone="success">{notice}</Notice> : null}
      {oauthMessage ? <Notice tone="error">{oauthMessage}</Notice> : null}
      <ProviderButtons next={next} />
      <p className="text-center text-sm text-muted">or with your email</p>
      <form className="grid gap-xs" noValidate onSubmit={onSubmit}>
        <TextField ref={emailRef} label="Email" type="email" name="email" autoComplete="email" inputMode="email" size="lg" error={email.error} {...email.inputProps} />
        <PasswordField ref={passwordRef} label="Password" name="password" autoComplete="current-password" size="lg" error={password.error} {...password.inputProps} />
        {copy ? (
          <Notice tone="error" title={copy.title}>
            {copy.message}
            {blocked ? (
              <>
                {' '}
                Try again in <span className="num">{retryLeft}</span>&nbsp;s.
              </>
            ) : null}
          </Notice>
        ) : null}
        {hasCode(error, 'EMAIL_NOT_VERIFIED') ? <ResendVerification email={email.value.trim()} /> : null}
        <div className="mt-xs flex flex-wrap items-center justify-between gap-md">
          <Button type="submit" variant="primary" size="lg" loading={pending} disabled={blocked}>
            Sign in
          </Button>
          <Link className="link text-sm" to="/forgot-password">
            Forgot your password?
          </Link>
        </div>
      </form>
    </AuthLayout>
  );
}
