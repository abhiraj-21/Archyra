import { useCallback, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  cancelSubscription,
  createOrder,
  createSubscription,
  getBillingPlans,
  getBillingRetention,
  getBillingStatus,
  getPayments,
  verifyOrder,
  verifySubscription,
} from '@/api/endpoints/billing';
import { queryKeys } from '@/api/queryKeys';
import type { BillingPeriod, BillingStatus, OrderVerification, SubscriptionVerification } from '@/api/types';
import { loadCheckout, openCheckout, type RazorpaySuccess } from '@/lib/razorpayCheckout';
import { useSession } from './sessionContext';

/** After a successful checkout the webhook may be the one that lands first: the status is polled for this long. */
export const CONFIRM_WAIT_MS = 30_000;
const CONFIRM_POLL_MS = 2_000;

export function useBillingPlans() {
  const { status } = useSession();
  return useQuery({ queryKey: queryKeys.billingPlans, queryFn: ({ signal }) => getBillingPlans(signal), enabled: status === 'authenticated', staleTime: 5 * 60_000 });
}

export function useBillingStatus() {
  const { status } = useSession();
  return useQuery({ queryKey: queryKeys.billingStatus, queryFn: ({ signal }) => getBillingStatus(signal), enabled: status === 'authenticated', staleTime: 60_000 });
}

export function useBillingRetention(enabled = true) {
  const { status } = useSession();
  return useQuery({ queryKey: queryKeys.billingRetention, queryFn: ({ signal }) => getBillingRetention(signal), enabled: enabled && status === 'authenticated' });
}

export function usePayments(page: number, size: number) {
  const { status } = useSession();
  return useQuery({ queryKey: queryKeys.billingPayments(page, size), queryFn: ({ signal }) => getPayments(page, size, signal), enabled: status === 'authenticated' });
}

/** Every successful billing change refreshes the billing queries, the usage (Premium is unlimited) and the current user (plan). */
function useAfterBillingChange() {
  const queryClient = useQueryClient();
  return useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.billing }),
      queryClient.invalidateQueries({ queryKey: queryKeys.usage }),
      queryClient.invalidateQueries({ queryKey: queryKeys.me }),
    ]);
  }, [queryClient]);
}

export function useCreateOrder() {
  return useMutation({ mutationFn: (period: BillingPeriod) => createOrder(period) });
}

export function useVerifyOrder() {
  const after = useAfterBillingChange();
  return useMutation({ mutationFn: (body: OrderVerification) => verifyOrder(body), onSuccess: after });
}

export function useCreateSubscription() {
  return useMutation({ mutationFn: (period: BillingPeriod) => createSubscription(period) });
}

export function useVerifySubscription() {
  const after = useAfterBillingChange();
  return useMutation({ mutationFn: (body: SubscriptionVerification) => verifySubscription(body), onSuccess: after });
}

export function useCancelSubscription() {
  const after = useAfterBillingChange();
  return useMutation({ mutationFn: () => cancelSubscription(), onSuccess: after });
}

/**
 * Where a payment is:
 * - `creating`: asking our server for an order or a subscription;
 * - `loading`: fetching Razorpay's checkout script;
 * - `checkout`: Razorpay's window is open;
 * - `confirming`: the checkout succeeded, our server is confirming it (verify, then up to 30 s of status polls);
 * - `done`: Premium was extended (`status` is the new one);
 * - `pending`: Razorpay took the payment but no confirmation reached us within 30 s (it will, through the webhook);
 * - `dismissed`: the customer closed the checkout (not an error);
 * - `failed`: Razorpay refused the payment (`reason` is its text) or a request failed (`error`).
 */
export type CheckoutPhase = 'idle' | 'creating' | 'loading' | 'checkout' | 'confirming' | 'done' | 'pending' | 'dismissed' | 'failed';

export interface CheckoutState {
  phase: CheckoutPhase;
  status?: BillingStatus;
  reason?: string;
  error?: unknown;
}

function moved(before: string | null | undefined, after: BillingStatus | undefined): boolean {
  return !!after && after.effectivePlan === 'PREMIUM' && !!after.planExpiresAt && after.planExpiresAt !== (before ?? null);
}

/**
 * The whole payment: create the order or subscription on our server (the amount is the server's), load checkout.js, open
 * Razorpay's checkout with the returned ids and key (prefilled with the user's name and email), verify on success, then poll the
 * status until Premium has moved or 30 seconds have passed.
 */
export function useRazorpayCheckout() {
  const { me } = useSession();
  const after = useAfterBillingChange();
  const [state, setState] = useState<CheckoutState>({ phase: 'idle' });
  const busy = useRef(false);

  const confirm = useCallback(
    async (before: string | null, verify: () => Promise<BillingStatus>) => {
      setState({ phase: 'confirming' });
      let latest: BillingStatus | undefined;
      try {
        latest = await verify();
      } catch {
        // the webhook may still confirm it: keep polling below
      }
      const deadline = Date.now() + CONFIRM_WAIT_MS;
      while (!moved(before, latest) && Date.now() < deadline) {
        await new Promise((resolve) => setTimeout(resolve, CONFIRM_POLL_MS));
        try {
          latest = await getBillingStatus();
        } catch {
          // a failed poll is retried until the deadline
        }
      }
      await after();
      setState(moved(before, latest) ? { phase: 'done', status: latest } : { phase: 'pending', status: latest });
    },
    [after],
  );

  const start = useCallback(
    async (period: BillingPeriod, mode: 'once' | 'auto', current: BillingStatus | undefined) => {
      if (busy.current) return;
      busy.current = true;
      const before = current?.planExpiresAt ?? null;
      const finish = (next: CheckoutState) => {
        busy.current = false;
        setState(next);
      };
      try {
        setState({ phase: 'creating' });
        const created = mode === 'once' ? await createOrder(period) : await createSubscription(period);
        setState({ phase: 'loading' });
        const Razorpay = await loadCheckout().catch(() => null);
        if (!Razorpay) {
          finish({ phase: 'failed', reason: 'The Razorpay checkout could not be loaded. Check your connection and try again.' });
          return;
        }
        setState({ phase: 'checkout' });
        const handler = (response: RazorpaySuccess) => {
          const verify =
            'orderId' in created
              ? () => verifyOrder({ razorpayOrderId: created.orderId, razorpayPaymentId: response.razorpay_payment_id, razorpaySignature: response.razorpay_signature })
              : () =>
                  verifySubscription({
                    razorpaySubscriptionId: created.subscriptionId,
                    razorpayPaymentId: response.razorpay_payment_id,
                    razorpaySignature: response.razorpay_signature,
                  });
          void confirm(before, verify).finally(() => {
            busy.current = false;
          });
        };
        openCheckout(
          Razorpay,
          {
            key: created.keyId,
            name: 'Archyra',
            description: `Premium, ${period === 'MONTHLY' ? 'monthly' : 'annual'}${mode === 'auto' ? ', renews automatically' : ''}`,
            ...('orderId' in created ? { order_id: created.orderId, amount: created.amount, currency: created.currency } : { subscription_id: created.subscriptionId }),
            prefill: { name: me?.name, email: me?.email },
            handler,
            // closing the window after a refused attempt keeps the refusal on screen; otherwise closing it is not an error
            modal: {
              ondismiss: () => {
                busy.current = false;
                setState((previous) => (previous.phase === 'failed' ? previous : { phase: 'dismissed' }));
              },
            },
          },
          (reason) => setState({ phase: 'failed', reason }),
        );
      } catch (error) {
        finish({ phase: 'failed', error });
      }
    },
    [confirm, me?.email, me?.name],
  );

  const reset = useCallback(() => {
    if (!busy.current) setState({ phase: 'idle' });
  }, []);

  return { state, start, reset };
}
