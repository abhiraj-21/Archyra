import { useLocation } from 'react-router';
import { Link } from 'react-router';
import { TextField } from '@/components/ui/Field';
import { AuthLayout } from './AuthLayout';
import { ResendVerification } from './ResendVerification';
import { emailError } from './validation';
import { useField } from './useField';

export function CheckEmailPage() {
  const state = useLocation().state as { email?: string } | null;
  const email = useField(emailError, state?.email ?? '');
  return (
    <AuthLayout
      title="Check your inbox"
      intro="If this address can be registered, we've sent you an email. Open the link in it to finish."
      footer={
        <Link className="link" to="/login">
          Back to sign in
        </Link>
      }
    >
      <div className="grid gap-sm">
        {/* True for every address: it never says whether the address already has an account. */}
        <p className="text-sm text-ink-2">
          Already have an account?{' '}
          <Link className="link" to="/login">
            Sign in
          </Link>
          , or{' '}
          <Link className="link" to="/forgot-password">
            reset your password
          </Link>
          .
        </p>
        <p className="text-sm text-muted">Nothing arrived after a few minutes? Check spam, or send another.</p>
        {state?.email ? null : <TextField label="Email" type="email" autoComplete="email" error={email.error} {...email.inputProps} />}
        <ResendVerification email={email.isValid() ? email.value.trim() : ''} />
      </div>
    </AuthLayout>
  );
}
