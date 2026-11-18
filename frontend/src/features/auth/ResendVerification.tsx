import { useState } from 'react';
import { Button } from '@/components/ui/Button';
import { useCountdown } from '@/components/ui/useCountdown';
import { Notice } from '@/components/ui/Notice';
import { useResendVerification } from '@/hooks/useAuthFlows';
import { describeError } from '@/lib/errorCopy';

const COOLDOWN_S = 60;

/** Asks for a new verification email. The answer is always the same generic 202; a cooldown stops repeat clicks. */
export function ResendVerification({ email, label = 'Send a new verification email' }: { email: string; label?: string }) {
  const resend = useResendVerification();
  const [cooldown, setCooldown] = useState<number | undefined>(undefined);
  const left = useCountdown(cooldown);
  const error = resend.error ? describeError(resend.error) : null;
  const waiting = left > 0;

  return (
    <div className="grid gap-sm">
      <div>
        <Button
          size="sm"
          loading={resend.isPending}
          disabled={waiting || !email}
          onClick={() =>
            resend.mutate(email, {
              onSuccess: () => setCooldown(COOLDOWN_S),
              onError: (e) => setCooldown((e as { retryAfter?: number }).retryAfter),
            })
          }
        >
          {waiting ? (
            <>
              Send again in <span className="num">{left}</span>&nbsp;s
            </>
          ) : (
            label
          )}
        </Button>
      </div>
      {resend.isSuccess && !error ? <p className="text-sm text-muted" role="status">{resend.data.message}</p> : null}
      {error ? <Notice tone="error" title={error.title}>{error.message}</Notice> : null}
    </div>
  );
}
