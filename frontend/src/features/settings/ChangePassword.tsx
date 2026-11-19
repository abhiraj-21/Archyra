import { useRef } from 'react';
import { Link } from 'react-router';
import { hasCode } from '@/api/errors';
import { Button } from '@/components/ui/Button';
import { PasswordField } from '@/components/ui/Field';
import { Notice } from '@/components/ui/Notice';
import { Skeleton } from '@/components/ui/Skeleton';
import { useToast } from '@/components/ui/toastContext';
import { useField } from '@/features/auth/useField';
import { changedPasswordError, PASSWORD_HINT } from '@/features/auth/validation';
import { useSession } from '@/hooks/sessionContext';
import { useChangePassword } from '@/hooks/useAccount';
import { useLinkedIdentities, useRefreshIdentities } from '@/hooks/useLinkedIdentities';
import { describeError } from '@/lib/errorCopy';

/**
 * Settings → Password. Asks for the current password (a stolen session alone must not be enough), then the new one
 * twice. Success replaces this browser's session and ends every other one, which the toast says. An account that only
 * signs in through Google or GitHub has no password to change: it is pointed to the email flow that sets a first one.
 */
export function ChangePassword() {
  const { me } = useSession();
  const identities = useLinkedIdentities();
  const refreshIdentities = useRefreshIdentities();
  const change = useChangePassword();
  const { showToast } = useToast();

  const current = useField((v) => (v ? null : 'Enter your current password.'));
  const next = useField((v) => changedPasswordError(v, current.value, me?.email));
  const confirm = useField((v) => (v === next.value ? null : 'The two passwords do not match.'));
  const refs = { current: useRef<HTMLInputElement>(null), next: useRef<HTMLInputElement>(null), confirm: useRef<HTMLInputElement>(null) };

  if (identities.isPending) return <Skeleton className="h-[7rem]" />;

  if (identities.data && !identities.data.hasPassword) {
    return (
      <p className="max-w-[34rem] text-sm text-muted">
        You sign in with Google or GitHub, so there is no password to change. To add one, use{' '}
        <Link to="/forgot-password" className="link">Forgot password</Link> with your account email.
      </p>
    );
  }

  const copy = change.error ? describeError(change.error) : null;
  const wrongCurrent = hasCode(change.error, 'INCORRECT_PASSWORD');
  const rejectedNew = hasCode(change.error, 'VALIDATION_ERROR');
  const notice = copy && !wrongCurrent && !rejectedNew ? copy : null;
  const clearServerError = () => {
    if (change.isError) change.reset();
  };

  return (
    <form
      className="grid max-w-[28rem] gap-xs"
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        current.touch();
        next.touch();
        confirm.touch();
        if (!current.isValid()) return refs.current.current?.focus();
        if (!next.isValid()) return refs.next.current?.focus();
        if (!confirm.isValid()) return refs.confirm.current?.focus();
        change.mutate(
          { currentPassword: current.value, newPassword: next.value },
          {
            onSuccess: () => {
              current.reset();
              next.reset();
              confirm.reset();
              showToast('Password changed. Your other devices have been signed out.');
            },
            onError: (error) => {
              if (hasCode(error, 'NO_PASSWORD_SET')) void refreshIdentities();
            },
          },
        );
      }}
    >
      <PasswordField
        ref={refs.current}
        label="Current password"
        name="current-password"
        autoComplete="current-password"
        error={current.error ?? (wrongCurrent ? copy?.message : null)}
        {...current.inputProps}
        onChange={(e) => {
          current.inputProps.onChange(e);
          clearServerError();
        }}
      />
      <PasswordField
        ref={refs.next}
        label="New password"
        name="new-password"
        autoComplete="new-password"
        help={PASSWORD_HINT}
        error={next.error ?? (rejectedNew ? copy?.message : null)}
        {...next.inputProps}
        onChange={(e) => {
          next.inputProps.onChange(e);
          clearServerError();
        }}
      />
      <PasswordField
        ref={refs.confirm}
        label="Repeat the new password"
        name="confirm-password"
        autoComplete="new-password"
        error={confirm.error}
        {...confirm.inputProps}
      />
      {notice ? (
        <Notice tone="error" title={notice.title}>
          {notice.message}
        </Notice>
      ) : null}
      <div>
        <Button type="submit" variant="primary" loading={change.isPending}>
          Change password
        </Button>
      </div>
    </form>
  );
}
