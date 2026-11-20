import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { Notice } from '@/components/ui/Notice';
import { useToast } from '@/components/ui/toastContext';
import { useCancelSubscription } from '@/hooks/useBilling';
import { exactDateTime } from '@/lib/billing';
import { describeError } from '@/lib/errorCopy';

/** Confirms cancelling auto-renew and says the exact date Premium then ends. Nothing is refunded and nothing ends early. */
export function CancelAutoRenewDialog({ open, onClose, endsAt }: { open: boolean; onClose: () => void; endsAt: string | null }) {
  const cancel = useCancelSubscription();
  const { showToast } = useToast();
  const error = cancel.error ? describeError(cancel.error) : null;
  return (
    <Dialog
      open={open}
      onClose={() => {
        cancel.reset();
        onClose();
      }}
      title="Cancel auto-renew?"
      footer={
        <>
          <Button variant="quiet" onClick={onClose}>
            Keep auto-renew
          </Button>
          <Button
            variant="danger"
            loading={cancel.isPending}
            onClick={() =>
              cancel.mutate(undefined, {
                onSuccess: () => {
                  showToast('Auto-renew cancelled. Premium stays until the end of the period you paid for.');
                  onClose();
                },
              })
            }
          >
            Cancel auto-renew
          </Button>
        </>
      }
    >
      <div className="grid gap-sm">
        <p>
          You will not be charged again. Premium stays until <strong className="font-medium text-ink">{exactDateTime(endsAt) || 'the end of this period'}</strong>, then
          your account moves to the Free plan.
        </p>
        <p className="text-sm text-muted">You can pay for another period by hand at any time.</p>
        {error ? <Notice tone="error" title={error.title}>{error.message}</Notice> : null}
      </div>
    </Dialog>
  );
}
