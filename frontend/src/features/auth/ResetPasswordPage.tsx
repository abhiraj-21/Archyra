import { useRef } from 'react';
import { Link, useNavigate } from 'react-router';
import { Button, ButtonLink } from '@/components/ui/Button';
import { PasswordField } from '@/components/ui/Field';
import { Notice } from '@/components/ui/Notice';
import { hasCode } from '@/api/errors';
import { useResetPassword } from '@/hooks/useAuthFlows';
import { describeError } from '@/lib/errorCopy';
import { AuthLayout } from './AuthLayout';
import { newPasswordError, PASSWORD_HINT } from './validation';
import { useField } from './useField';
import { useTokenFromUrl } from './useTokenFromUrl';

export function ResetPasswordPage() {
  const token = useTokenFromUrl();
  const navigate = useNavigate();
  const reset = useResetPassword();
  const password = useField((v) => newPasswordError(v));
  const confirm = useField((v) => (v === password.value ? null : 'The two passwords do not match.'));
  const refs = { password: useRef<HTMLInputElement>(null), confirm: useRef<HTMLInputElement>(null) };

  if (!token || hasCode(reset.error, 'INVALID_OR_EXPIRED_TOKEN')) {
    return (
      <AuthLayout title="This link has expired" intro="Reset links work once and expire after 30 minutes. Ask for a new one." noReferrer>
        <div>
          <ButtonLink to="/forgot-password" variant="primary" size="lg">
            Send a new link
          </ButtonLink>
        </div>
      </AuthLayout>
    );
  }

  // A rejected new password leaves the link usable, so the form stays and shows why.
  const copy = reset.error ? describeError(reset.error) : null;
  const serverPasswordError = hasCode(reset.error, 'VALIDATION_ERROR') ? copy?.message : null;

  return (
    <AuthLayout title="Choose a new password" intro="Signing in again is required everywhere once it changes." noReferrer footer={<Link className="link" to="/login">Back to sign in</Link>}>
      <form
        className="grid gap-xs"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          password.touch();
          confirm.touch();
          if (!password.isValid()) return refs.password.current?.focus();
          if (!confirm.isValid()) return refs.confirm.current?.focus();
          reset.mutate(
            { token, newPassword: password.value },
            { onSuccess: () => navigate('/login', { replace: true, state: { notice: 'Your password has changed and every session has ended. Sign in with the new password.' } }) },
          );
        }}
      >
        <PasswordField ref={refs.password} label="New password" autoComplete="new-password" size="lg" help={PASSWORD_HINT} error={password.error ?? serverPasswordError ?? null} {...password.inputProps} />
        <PasswordField ref={refs.confirm} label="Repeat the new password" autoComplete="new-password" size="lg" error={confirm.error} {...confirm.inputProps} />
        {copy && !serverPasswordError ? <Notice tone="error" title={copy.title}>{copy.message}</Notice> : null}
        <div className="mt-xs">
          <Button type="submit" variant="primary" size="lg" loading={reset.isPending}>
            Change password
          </Button>
        </div>
      </form>
    </AuthLayout>
  );
}
