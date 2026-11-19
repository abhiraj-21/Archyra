import { useEffect, useState } from 'react';
import { Trash2 } from 'lucide-react';
import { ApiError, hasCode } from '@/api/errors';
import { Button, ButtonLink } from '@/components/ui/Button';
import { Notice } from '@/components/ui/Notice';
import { useDocumentTitle } from '@/components/ui/useDocumentTitle';
import { useConfirmAccountDeletion } from '@/hooks/useAccount';
import { describeDeleteError } from '@/lib/errorCopy';
import { DeletionConsequences } from './DeleteAccount';

/**
 * `/settings/delete-confirm?token=…`, the page the emailed link opens for an account without a password. The token is
 * read once and removed from the address bar; nothing is deleted until the person presses the button (a link that deleted
 * on load could be triggered by a prefetch or a mail scanner). The route is behind sign-in: signing in first and coming back
 * keeps the link, and a token only works for the user it was sent to.
 */
export default function DeleteConfirmPage() {
  useDocumentTitle('Confirm account deletion');
  // Read in the initializer (a pure read: StrictMode runs it twice), dropped from the address bar in an effect.
  const [token] = useState(() => new URLSearchParams(window.location.search).get('token'));
  useEffect(() => {
    if (window.location.search) window.history.replaceState(window.history.state, '', window.location.pathname);
  }, []);
  const confirm = useConfirmAccountDeletion();

  // A link with no token, or one the server calls expired or used, cannot work again: send the person back to Settings.
  const linkIsDead = !token || hasCode(confirm.error, 'INVALID_OR_EXPIRED_TOKEN', 'DELETE_METHOD_MISMATCH');
  const copy = confirm.error ? describeDeleteError(confirm.error) : token ? null : describeDeleteError(new ApiError(400, 'INVALID_OR_EXPIRED_TOKEN', ''));

  return (
    <div className="mx-auto grid w-full max-w-[40rem] gap-md px-md py-lg sm:px-lg lg:py-xl">
      <header className="grid gap-2xs">
        <h1 className="text-2xl">Confirm account deletion</h1>
        <p className="text-muted">You asked to delete your Archyra account. This cannot be undone.</p>
      </header>
      <DeletionConsequences />
      {copy ? (
        <Notice tone="error" title={copy.title}>
          {copy.message}
        </Notice>
      ) : null}
      <div className="flex flex-wrap gap-sm">
        {linkIsDead ? null : (
          <Button variant="danger" icon={<Trash2 size={16} aria-hidden="true" />} loading={confirm.isPending} onClick={() => token && confirm.mutate(token)}>
            Delete my account permanently
          </Button>
        )}
        <ButtonLink to="/settings" variant={linkIsDead ? 'primary' : 'secondary'}>
          {linkIsDead ? 'Back to Settings' : 'Keep my account'}
        </ButtonLink>
      </div>
    </div>
  );
}
