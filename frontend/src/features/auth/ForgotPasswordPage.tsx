import { useRef } from 'react';
import { Link } from 'react-router';
import { Button } from '@/components/ui/Button';
import { TextField } from '@/components/ui/Field';
import { Notice } from '@/components/ui/Notice';
import { useForgotPassword } from '@/hooks/useAuthFlows';
import { describeError } from '@/lib/errorCopy';
import { AuthLayout } from './AuthLayout';
import { emailError } from './validation';
import { useField } from './useField';

export function ForgotPasswordPage() {
  const forgot = useForgotPassword();
  const email = useField(emailError);
  const ref = useRef<HTMLInputElement>(null);
  const copy = forgot.error ? describeError(forgot.error) : null;

  if (forgot.isSuccess) {
    return (
      <AuthLayout title="Check your inbox" intro={forgot.data.message} footer={<Link className="link" to="/login">Back to sign in</Link>}>
        <p className="text-sm text-muted">The link works once and expires after 30 minutes.</p>
      </AuthLayout>
    );
  }

  return (
    <AuthLayout
      title="Reset your password"
      intro="Enter your email and we will send a reset link. Accounts that sign in with Google or GitHub can add a password this way."
      footer={<Link className="link" to="/login">Back to sign in</Link>}
    >
      <form
        className="grid gap-xs"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          email.touch();
          if (!email.isValid()) return ref.current?.focus();
          forgot.mutate(email.value.trim());
        }}
      >
        <TextField ref={ref} label="Email" type="email" autoComplete="email" inputMode="email" size="lg" error={email.error} {...email.inputProps} />
        {copy ? <Notice tone="error" title={copy.title}>{copy.message}</Notice> : null}
        <div className="mt-xs">
          <Button type="submit" variant="primary" size="lg" loading={forgot.isPending}>
            Send reset link
          </Button>
        </div>
      </form>
    </AuthLayout>
  );
}
