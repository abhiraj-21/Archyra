import { useRef } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { Button } from '@/components/ui/Button';
import { PasswordField, TextField } from '@/components/ui/Field';
import { Notice } from '@/components/ui/Notice';
import { useRegister } from '@/hooks/useAuthFlows';
import { describeError } from '@/lib/errorCopy';
import { safeNext } from '@/lib/returnTo';
import { AuthLayout } from './AuthLayout';
import { ProviderButtons } from './ProviderButtons';
import { emailError, nameError, newPasswordError, PASSWORD_HINT } from './validation';
import { useField } from './useField';

export function RegisterPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeNext(params.get('next'));
  const register = useRegister();
  const name = useField(nameError);
  const email = useField(emailError);
  const password = useField((v) => newPasswordError(v, email.value));
  const refs = { name: useRef<HTMLInputElement>(null), email: useRef<HTMLInputElement>(null), password: useRef<HTMLInputElement>(null) };

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    for (const f of [name, email, password]) f.touch();
    if (!name.isValid()) return refs.name.current?.focus();
    if (!email.isValid()) return refs.email.current?.focus();
    if (!password.isValid()) return refs.password.current?.focus();
    register.mutate(
      { name: name.value.trim(), email: email.value.trim(), password: password.value },
      // Always the same generic answer: the UI never learns or says whether the address already has an account.
      { onSuccess: () => navigate('/register/check-email', { state: { email: email.value.trim() } }) },
    );
  }

  const copy = register.error ? describeError(register.error) : null;

  return (
    <AuthLayout
      title="Create your account"
      intro="Analyse a GitHub repository and see its architecture as a graph."
      footer={
        <>
          Already have an account?{' '}
          <Link className="link" to="/login">
            Sign in
          </Link>
        </>
      }
    >
      <ProviderButtons next={next} />
      <p className="text-center text-sm text-muted">or with your email</p>
      <form className="grid gap-xs" noValidate onSubmit={onSubmit}>
        <TextField ref={refs.name} label="Name" name="name" autoComplete="name" size="lg" maxLength={100} error={name.error} {...name.inputProps} />
        <TextField ref={refs.email} label="Email" type="email" name="email" autoComplete="email" inputMode="email" size="lg" error={email.error} {...email.inputProps} />
        <PasswordField ref={refs.password} label="Password" name="new-password" autoComplete="new-password" size="lg" help={PASSWORD_HINT} error={password.error} {...password.inputProps} />
        {copy ? (
          <Notice tone="error" title={copy.title}>
            {copy.message}
          </Notice>
        ) : null}
        <div className="mt-xs">
          <Button type="submit" variant="primary" size="lg" loading={register.isPending}>
            Create account
          </Button>
        </div>
      </form>
    </AuthLayout>
  );
}
