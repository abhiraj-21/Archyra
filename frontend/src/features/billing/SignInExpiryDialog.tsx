import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { Button } from '@/components/ui/Button';
import { Dialog } from '@/components/ui/Dialog';
import { useBillingPlans, useBillingRetention, useBillingStatus } from '@/hooks/useBilling';
import { expiryNotice } from '@/lib/billing';
import { clearSignedIn, peekSignedIn } from '@/lib/signInNotice';
import { RetentionList } from './RetentionList';

/**
 * Once right after each interactive sign-in (the flag `lib/signInNotice` sets where a sign-in completes), never after a silent
 * session refresh: the same warning as the banner, with the repositories that would be kept and deleted. Only for EXPIRING and
 * GRACE. The flag is read in the state initialiser (a pure read: StrictMode runs it twice) and cleared in an effect.
 */
export function SignInExpiryDialog() {
  const [signedIn] = useState(peekSignedIn);
  const [closed, setClosed] = useState(false);
  const navigate = useNavigate();
  const status = useBillingStatus();
  const plans = useBillingPlans();
  const notice = expiryNotice(status.data, plans.data?.keepRepositories ?? 5);
  const retention = useBillingRetention(signedIn && !!notice);

  useEffect(() => {
    if (signedIn) clearSignedIn();
  }, [signedIn]);

  const open = signedIn && !closed && !!notice && !!retention.data;
  if (!notice || !retention.data) return null;
  return (
    <Dialog
      open={open}
      onClose={() => setClosed(true)}
      size="wide"
      title={notice.title}
      footer={
        <>
          <Button variant="quiet" onClick={() => setClosed(true)}>
            Later
          </Button>
          <Button
            variant="primary"
            onClick={() => {
              setClosed(true);
              navigate('/upgrade');
            }}
          >
            Renew
          </Button>
        </>
      }
    >
      <div className="grid gap-md">
        <p>{notice.body}</p>
        {notice.atRisk > 0 ? <RetentionList kept={retention.data.kept} atRisk={retention.data.atRisk} keep={retention.data.keep} /> : null}
      </div>
    </Dialog>
  );
}
