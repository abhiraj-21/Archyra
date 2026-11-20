import { http } from '../http';
import type {
  BillingPeriod,
  BillingPlans,
  BillingRetention,
  BillingStatus,
  CreatedOrder,
  CreatedSubscription,
  OrderVerification,
  PaymentPage,
  SubscriptionVerification,
} from '../types';

/** Premium through Razorpay. The client never sends an amount: only the period, and what Razorpay's checkout returned. */
export const getBillingPlans = (signal?: AbortSignal) => http<BillingPlans>('/billing/plans', { signal });
export const getBillingStatus = (signal?: AbortSignal) => http<BillingStatus>('/billing/status', { signal });
export const getBillingRetention = (signal?: AbortSignal) => http<BillingRetention>('/billing/retention', { signal });
export const getPayments = (page: number, size: number, signal?: AbortSignal) =>
  http<PaymentPage>('/billing/payments', { query: { page, size }, signal });

export const createOrder = (period: BillingPeriod) => http<CreatedOrder>('/billing/orders', { method: 'POST', body: { period } });
export const verifyOrder = (body: OrderVerification) => http<BillingStatus>('/billing/orders/verify', { method: 'POST', body });
export const createSubscription = (period: BillingPeriod) =>
  http<CreatedSubscription>('/billing/subscriptions', { method: 'POST', body: { period } });
export const verifySubscription = (body: SubscriptionVerification) =>
  http<BillingStatus>('/billing/subscriptions/verify', { method: 'POST', body });
export const cancelSubscription = () => http<BillingStatus>('/billing/subscriptions/cancel', { method: 'POST' });
