/**
 * Razorpay Standard Checkout. Its script is the one third-party request the app makes, and only from the payment screen, only
 * when a payment starts (`loadCheckout`), never on another page (DESIGN-NOTES.md → Third-party script). It shows the payment
 * methods enabled on the Razorpay account. No `theme.color` is passed: colour literals are not allowed in `src/`, and Razorpay's
 * default is fine.
 */
export const CHECKOUT_SCRIPT = 'https://checkout.razorpay.com/v1/checkout.js';

export interface RazorpaySuccess {
  razorpay_payment_id: string;
  razorpay_order_id?: string;
  razorpay_subscription_id?: string;
  razorpay_signature: string;
}

export interface RazorpayFailure {
  error?: { description?: string; reason?: string };
}

export interface CheckoutOptions {
  key: string;
  name: string;
  description: string;
  order_id?: string;
  subscription_id?: string;
  amount?: number;
  currency?: string;
  prefill?: { name?: string; email?: string };
  handler: (response: RazorpaySuccess) => void;
  modal?: { ondismiss?: () => void; escape?: boolean };
}

interface RazorpayInstance {
  open: () => void;
  on: (event: 'payment.failed', callback: (response: RazorpayFailure) => void) => void;
}

type RazorpayConstructor = new (options: CheckoutOptions) => RazorpayInstance;

declare global {
  interface Window {
    Razorpay?: RazorpayConstructor;
  }
}

let loading: Promise<RazorpayConstructor> | null = null;

/** Adds checkout.js once and resolves with Razorpay's constructor. A failed load can be tried again. */
export function loadCheckout(): Promise<RazorpayConstructor> {
  if (window.Razorpay) return Promise.resolve(window.Razorpay);
  if (loading) return loading;
  loading = new Promise<RazorpayConstructor>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = CHECKOUT_SCRIPT;
    script.async = true;
    script.onload = () => (window.Razorpay ? resolve(window.Razorpay) : reject(new Error('checkout unavailable')));
    script.onerror = () => {
      script.remove();
      reject(new Error('checkout unavailable'));
    };
    document.head.appendChild(script);
  });
  loading.catch(() => {
    loading = null;
  });
  return loading;
}

/** Opens the checkout. `onFailed` gets Razorpay's own reason text (shown as text, never as HTML). */
export function openCheckout(Razorpay: RazorpayConstructor, options: CheckoutOptions, onFailed: (reason: string) => void): void {
  const instance = new Razorpay(options);
  instance.on('payment.failed', (response) => onFailed(response.error?.description || 'The payment did not go through.'));
  instance.open();
}
