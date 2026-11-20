import { useId, useRef, useState } from 'react';
import { MailCheck, Trash2 } from 'lucide-react';
import { hasCode } from '@/api/errors';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { PasswordField, TextField } from '@/components/ui/Field';
import { Notice } from '@/components/ui/Notice';
import { Skeleton } from '@/components/ui/Skeleton';
import { useField } from '@/features/auth/useField';
import { useSession } from '@/hooks/sessionContext';
import { useDeleteAccount, useRequestAccountDeletion } from '@/hooks/useAccount';
import { useLinkedIdentities } from '@/hooks/useLinkedIdentities';
import { describeDeleteError } from '@/lib/errorCopy';
import { canConfirmDeletion, DELETE_WORD } from './deleteGate';

/** What deleting does, in one place: the Settings section and the emailed-link page say the same things. */
export function DeletionConsequences() {
  return (
    <ul className="grid list-disc gap-2xs pl-md text-sm text-ink-2">
      <li>Your analyses, their graphs and your Ask AI conversations are deleted permanently.</li>
      <li>
        <strong className="font-medium text-ink">Your usage is not reset.</strong> Creating a new account later keeps the analyses and
        questions you already used.
      </li>
      <li>Paid plans are not carried over: a new account starts on the free plan.</li>
      <li>
        To make that possible Archyra keeps the number of analyses and questions you used and the links of the repositories you had
        analysed, under a one-way hash of your email and linked sign-in methods. If you come back, those repositories return as
        &ldquo;Not analysed&rdquo; entries that you can analyse or delete. No code, graph or conversation is kept, and no name or
        email address.
      </li>
    </ul>
  );
}

/**
 * Settings → Danger zone. A button opens a confirm dialog (a native modal: focus is trapped and Escape closes it).
 * Deleting needs the word DELETE typed and, for an account with a password, that password; an account that signs in with
 * Google or GitHub only has no password, so it asks the server to email a confirmation link instead and shows a sent state
 * (the link opens `/settings/delete-confirm`, which finishes the job).
 */
export function DeleteAccount() {
  const identities = useLinkedIdentities();
  const [open, setOpen] = useState(false);

  if (identities.isPending) return <Skeleton className="h-[5rem]" />;
  if (identities.isError) {
    return (
      <Notice tone="error" title="Could not load your sign-in methods">
        Deleting your account needs to know how you sign in. Reload the page and try again.
      </Notice>
    );
  }

  return (
    <div className="grid max-w-[34rem] gap-sm">
      <p className="text-sm text-ink-2">
        Permanently delete your account and everything you analysed. Your used analyses and questions are still counted if you sign up again.
      </p>
      <div>
        <Button variant="danger" icon={<Trash2 size={16} aria-hidden="true" />} onClick={() => setOpen(true)}>
          Delete account
        </Button>
      </div>
      <DeleteDialog open={open} onClose={() => setOpen(false)} hasPassword={identities.data.hasPassword} />
    </div>
  );
}

function DeleteDialog({ open, onClose, hasPassword }: { open: boolean; onClose: () => void; hasPassword: boolean }) {
  const { me } = useSession();
  const formId = useId();
  const deleteNow = useDeleteAccount();
  const requestLink = useRequestAccountDeletion();
  const word = useField((v) => (v === DELETE_WORD ? null : `Type ${DELETE_WORD} to confirm.`));
  const password = useField((v) => (v ? null : 'Enter your password.'));
  const passwordRef = useRef<HTMLInputElement>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);

  const mutation = hasPassword ? deleteNow : requestLink;
  const copy = mutation.error ? describeDeleteError(mutation.error) : null;
  const wrongPassword = hasCode(mutation.error, 'INVALID_CREDENTIALS');
  const notice = copy && !wrongPassword ? copy : null;
  const confirmed = canConfirmDeletion(word.value, password.value, hasPassword);

  function close() {
    word.reset();
    password.reset();
    deleteNow.reset();
    requestLink.reset();
    setSentTo(null);
    onClose();
  }

  function submit() {
    word.touch();
    password.touch();
    if (!confirmed) return;
    if (hasPassword) {
      deleteNow.mutate(password.value, { onError: () => passwordRef.current?.select() });
    } else {
      requestLink.mutate(undefined, { onSuccess: () => setSentTo(me?.email ?? '') });
    }
  }

  if (sentTo !== null) {
    return (
      <Dialog open={open} onClose={close} title="Check your email" footer={<Button variant="primary" onClick={close}>Close</Button>}>
        <div className="grid gap-sm">
          <p className="flex items-start gap-xs" role="status">
            <MailCheck size={18} className="mt-3xs shrink-0" aria-hidden="true" />
            <span>
              We sent a confirmation link{sentTo ? <> to <strong className="font-medium text-ink">{sentTo}</strong></> : null}. Open it to finish deleting your
              account. The link works once and expires in 15 minutes. Until then, nothing is deleted.
            </span>
          </p>
          <p className="text-sm text-muted">Nothing arrived? Close this and ask for a new link: the newest one is the only one that works.</p>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog
      open={open}
      onClose={close}
      title="Delete your account?"
      footer={
        <>
          <Button onClick={close}>Cancel</Button>
          <Button type="submit" form={formId} variant="danger" disabled={!confirmed} loading={mutation.isPending}>
            {hasPassword ? 'Delete account' : 'Email me a confirmation link'}
          </Button>
        </>
      }
    >
      <form
        id={formId}
        className="grid gap-sm"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <p>This cannot be undone.</p>
        <DeletionConsequences />
        {hasPassword ? null : <p className="text-sm text-ink-2">We&rsquo;ll email you a confirmation link, because there is no password to ask for.</p>}
        <TextField
          label={`Type ${DELETE_WORD} to confirm`}
          name="confirm-delete"
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          error={word.error}
          {...word.inputProps}
          onChange={(e) => {
            word.inputProps.onChange(e);
            if (mutation.isError) mutation.reset();
          }}
        />
        {hasPassword ? (
          <PasswordField
            ref={passwordRef}
            label="Your password"
            name="current-password"
            autoComplete="current-password"
            error={password.error ?? (wrongPassword ? copy?.message : null)}
            {...password.inputProps}
            onChange={(e) => {
              password.inputProps.onChange(e);
              if (mutation.isError) mutation.reset();
            }}
          />
        ) : null}
        {notice ? (
          <Notice tone="error" title={notice.title}>
            {notice.message}
          </Notice>
        ) : null}
      </form>
    </Dialog>
  );
}
