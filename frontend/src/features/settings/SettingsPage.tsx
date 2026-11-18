import { useState } from 'react';
import { useNavigate } from 'react-router';
import { LogOut, Sparkles } from 'lucide-react';
import { Button } from '@/components/ui/Button';
import { PlanBadge } from '@/components/ui/Chips';
import { TextField } from '@/components/ui/Field';
import { Notice } from '@/components/ui/Notice';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useSession } from '@/hooks/sessionContext';
import { useUpdateName } from '@/hooks/useAccount';
import { useUpgrade } from '@/hooks/upgradeContext';
import { describeError } from '@/lib/errorCopy';
import { UsageMeter } from '@/features/usage/UsageMeter';
import { nameError } from '@/features/auth/validation';

const PROVIDER_LABEL = { GOOGLE: 'Google', GITHUB: 'GitHub' } as const;

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="grid gap-md border-t border-rule py-lg md:grid-cols-[14rem_minmax(0,1fr)]">
      <h2 className="text-lg">{title}</h2>
      <div className="grid content-start gap-md">{children}</div>
    </section>
  );
}

export default function SettingsPage() {
  useDocumentTitle('Settings');
  const { me, signOut } = useSession();
  const navigate = useNavigate();
  const { openUpgrade } = useUpgrade();
  const update = useUpdateName();
  const [name, setName] = useState(me?.name ?? '');
  const [touched, setTouched] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  if (!me) return null;

  const error = touched ? nameError(name) : null;
  const unchanged = name.trim() === me.name;
  const serverError = update.error ? describeError(update.error) : null;

  return (
    <div className="mx-auto grid w-full max-w-[56rem] px-md py-lg sm:px-lg lg:py-xl">
      <header className="grid gap-2xs pb-lg">
        <h1 className="text-2xl">Settings</h1>
        <p className="text-muted">Your account, plan and sign-in methods.</p>
      </header>

      <Section title="Profile">
        <form
          className="grid max-w-[28rem] gap-xs"
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            setTouched(true);
            if (nameError(name) || unchanged) return;
            update.mutate(name.trim());
          }}
        >
          <TextField
            label="Name"
            name="name"
            autoComplete="name"
            maxLength={100}
            value={name}
            error={error}
            help={update.isSuccess && unchanged ? 'Name saved.' : 'Shown in Archyra only.'}
            onChange={(e) => { setName(e.target.value); if (update.isSuccess || update.isError) update.reset(); }}
            onBlur={() => setTouched(true)}
          />
          {serverError ? <Notice tone="error" title={serverError.title}>{serverError.message}</Notice> : null}
          <div>
            <Button type="submit" variant="primary" loading={update.isPending}>
              Save name
            </Button>
          </div>
        </form>
        <dl className="grid gap-3xs text-sm">
          <dt className="text-muted">Email</dt>
          <dd className="flex flex-wrap items-center gap-xs text-ink">
            {me.email}
            <span className="chip" data-tone={me.emailVerified ? 'success' : 'warn'}>{me.emailVerified ? 'Verified' : 'Not verified'}</span>
          </dd>
          <dd className="text-xs text-muted">Your email cannot be changed here.</dd>
        </dl>
      </Section>

      <Section title="Plan">
        <div className="flex flex-wrap items-center gap-sm">
          <PlanBadge plan={me.plan} />
          <span className="text-ink-2">{me.plan === 'PREMIUM' ? 'You are on Premium.' : 'You are on the free plan.'}</span>
        </div>
        {me.plan === 'FREE' ? (
          <div>
            <Button icon={<Sparkles size={16} aria-hidden="true" />} onClick={() => openUpgrade()}>
              Upgrade
            </Button>
          </div>
        ) : null}
        <div className="max-w-[24rem]">
          <UsageMeter />
        </div>
      </Section>

      <Section title="Sign-in methods">
        {me.linkedProviders.length ? (
          <ul className="grid gap-xs">
            {me.linkedProviders.map((p) => (
              <li key={p} className="flex items-center gap-sm text-sm text-ink">
                {PROVIDER_LABEL[p]}
                <span className="chip" data-tone="success">Linked</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted">No Google or GitHub account is linked. Signing in with one that uses this email links it.</p>
        )}
      </Section>

      <Section title="Session">
        <div>
          <Button
            icon={<LogOut size={16} aria-hidden="true" />}
            loading={signingOut}
            onClick={async () => {
              setSigningOut(true);
              await signOut();
              navigate('/login', { replace: true });
            }}
          >
            Sign out
          </Button>
        </div>
      </Section>
    </div>
  );
}
