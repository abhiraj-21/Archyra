import { isApiError, type ErrorCode } from '@/api/errors';
import { outOfCreditsMessage, resetWait } from './resetTime';

/**
 * What the UI says and does for each machine-readable error code. Decisions are made from `code` only; the
 * server's `message` is shown only for VALIDATION_ERROR, where it lists the allowed values and never echoes input.
 */
export interface ErrorCopy {
  code: ErrorCode;
  title: string;
  message: string;
  /** A retry button makes sense (network, timeout, 5xx, busy). */
  retryable: boolean;
  /** Seconds to wait, from Retry-After. */
  retryAfter?: number;
}

type Entry = Omit<ErrorCopy, 'code' | 'retryAfter'>;

const COPY: Record<string, Entry> = {
  AUTHENTICATION_REQUIRED: { title: 'Your session ended', message: 'Sign in again to continue.', retryable: false },
  REFRESH_TOKEN_INVALID: { title: 'Your session ended', message: 'Sign in again to continue.', retryable: false },
  ACCESS_DENIED: { title: 'Not available', message: 'Your account cannot open this.', retryable: false },
  CSRF_TOKEN_INVALID: { title: 'Security check failed', message: 'Reload the page and try again.', retryable: true },
  INVALID_CREDENTIALS: { title: 'Sign-in failed', message: 'That email and password do not match an account.', retryable: false },
  EMAIL_NOT_VERIFIED: { title: 'Verify your email first', message: 'Open the link we emailed you, or send a new one.', retryable: false },
  ACCOUNT_DISABLED: { title: 'Account disabled', message: 'This account has been disabled. Contact the administrator.', retryable: false },
  INVALID_OR_EXPIRED_TOKEN: { title: 'This link has expired', message: 'Links work once and expire. Ask for a new one.', retryable: false },
  PREMIUM_FEATURE: { title: 'Premium feature', message: 'This needs Archyra Premium.', retryable: false },
  USAGE_LIMIT_EXCEEDED: {
    title: 'Free analyses used up',
    message: 'You have used every free analysis. Delete an old analysis to get its credit back.',
    retryable: false,
  },
  RATE_LIMITED: { title: 'Too many attempts', message: 'Wait a moment, then try again.', retryable: true },
  SERVICE_BUSY: { title: 'The queue is full', message: 'Archyra is busy analysing other repositories. Try again shortly.', retryable: true },
  VALIDATION_ERROR: { title: 'Check the details', message: 'Something in the request was not accepted.', retryable: false },
  INVALID_REQUEST_BODY: { title: 'Request not understood', message: 'Reload the page and try again.', retryable: false },
  ANALYSIS_NOT_FOUND: { title: 'Analysis not found', message: 'It may have been deleted, or the link is wrong.', retryable: false },
  NODE_NOT_FOUND: { title: 'Not in this graph', message: 'This item is not part of the analysed repository.', retryable: false },
  USER_NOT_FOUND: { title: 'User not found', message: 'This account does not exist.', retryable: false },
  ANALYSIS_NOT_COMPLETED: { title: 'Still analysing', message: 'Results appear when the analysis completes.', retryable: true },
  ANALYSIS_IN_PROGRESS: {
    title: 'Analysis still running',
    message: 'A running analysis cannot be deleted. Wait until it completes or fails.',
    retryable: false,
  },
  ANALYSIS_NOT_RESTORABLE: {
    title: 'Already analysed or running',
    message: 'Only a repository that has not been analysed yet can be analysed with its saved credit. Reload to see its current state.',
    retryable: false,
  },
  CANNOT_DISABLE_SELF: { title: 'You cannot disable yourself', message: 'Ask another administrator to do it.', retryable: false },
  LAST_ADMIN: { title: 'Last administrator', message: 'At least one enabled administrator must remain.', retryable: false },
  PROVIDER_ALREADY_LINKED: { title: 'Already linked', message: 'A sign-in of that provider is already linked to your account.', retryable: false },
  LAST_LOGIN_METHOD: {
    title: 'This is your only way to sign in',
    message: 'Set a password or link another provider first, then you can remove this one.',
    retryable: false,
  },
  IDENTITY_NOT_FOUND: { title: 'Not linked', message: 'That provider is not linked to your account any more.', retryable: false },
  INCORRECT_PASSWORD: { title: 'Wrong current password', message: 'That is not your current password.', retryable: false },
  NO_PASSWORD_SET: {
    title: 'No password yet',
    message: 'This account signs in with Google or GitHub. Use Forgot password with your account email to set one.',
    retryable: false,
  },
  DELETE_METHOD_MISMATCH: {
    title: 'Use the other way to delete',
    message: 'Reload Settings: it shows the way that fits how your account signs in.',
    retryable: false,
  },
  AI_NOT_READY: { title: 'Ask AI is not ready', message: 'This repository is still being prepared for questions. Check its status and try again.', retryable: true },
  AI_REANALYZE_REQUIRED: { title: 'Re-analyze to enable Ask AI', message: 'This analysis cannot be prepared for questions again. Re-analyze the repository to enable Ask AI.', retryable: false },
  AI_UNAVAILABLE: { title: 'The AI service is busy', message: 'The AI service is busy right now. Your question wasn\'t counted. Try again in a minute.', retryable: true },
  QUESTION_NOT_ANSWERABLE: {
    title: 'Nothing in this repository matches that',
    message:
      "Not counted: nothing was sent to the AI and it didn't use one of your questions. Edit the question to name a class, a file, or how something works. \"What to ask\" has ideas.",
    retryable: false,
  },
  CONVERSATION_NOT_FOUND: { title: 'Conversation not found', message: 'It may have been deleted. Start a new conversation.', retryable: false },
  CONVERSATION_FULL: { title: 'This conversation is full', message: 'Start a new conversation to keep asking.', retryable: false },
  CONVERSATION_BUSY: { title: 'Still answering', message: 'Wait for the current answer to finish.', retryable: true },
  TOO_MANY_CONVERSATIONS: { title: 'Too many conversations', message: 'Delete a conversation you no longer need to start another.', retryable: false },
  BILLING_UNAVAILABLE: { title: 'Payments are not available right now', message: 'Nothing was charged. Try again later.', retryable: true },
  PAYMENT_VERIFICATION_FAILED: {
    title: 'The payment could not be confirmed here',
    message: 'If money left your account, Premium starts as soon as Razorpay confirms the payment. Check this page again in a minute.',
    retryable: false,
  },
  SUBSCRIPTION_ACTIVE: {
    title: 'Premium already renews automatically',
    message: 'Cancel auto-renew first if you want to pay another way.',
    retryable: false,
  },
  NO_ACTIVE_SUBSCRIPTION: { title: 'Nothing to cancel', message: 'You have no auto-renewing subscription. Reload to see your plan.', retryable: false },
  INVALID_PERIOD: { title: 'Choose monthly or annual', message: 'Pick a period and try again.', retryable: false },
  NETWORK_ERROR: { title: 'Cannot reach Archyra', message: 'Check your connection, then try again.', retryable: true },
  TIMEOUT: { title: 'That took too long', message: 'The server did not answer in time. Try again.', retryable: true },
  UNEXPECTED_RESPONSE: { title: 'Unreadable answer', message: 'The server sent an answer that could not be read.', retryable: true },
};

const GENERIC: Entry = { title: 'Something went wrong', message: 'The request failed. Try again in a moment.', retryable: true };

/**
 * `USAGE_LIMIT_EXCEEDED` is two limits: the lifetime analyses (no `resetsAt`, the copy above) and the weekly Ask AI questions,
 * whose body carries `resetsAt`; that one says when the questions are back, computed here from the instant in the reader's time
 * zone (`now` is a parameter for the tests).
 */
export function describeError(error: unknown, now: number = Date.now()): ErrorCopy {
  if (!isApiError(error)) return { code: 'UNKNOWN', ...GENERIC };
  if (error.code === 'USAGE_LIMIT_EXCEEDED' && error.resetsAt) {
    return { code: error.code, title: 'Out of credits', message: outOfCreditsMessage(resetWait(error.resetsAt, now)?.text ?? null), retryable: false };
  }
  const entry = COPY[error.code] ?? (error.status >= 500 ? GENERIC : { ...GENERIC, retryable: false });
  const message = error.code === 'VALIDATION_ERROR' && error.message ? error.message : entry.message;
  return { code: error.code, ...entry, message, retryAfter: error.retryAfter };
}

/**
 * Copy for the delete-account flow, where the generic copy would be wrong: a wrong password is not a sign-in failure,
 * and an expired link means asking for a new one from Settings. Anything else is the ordinary copy.
 */
const DELETE_COPY: Record<string, Entry> = {
  INVALID_CREDENTIALS: { title: 'Wrong password', message: 'That is not your password. Nothing was deleted.', retryable: false },
  LAST_ADMIN: {
    title: 'You are the last administrator',
    message: 'At least one enabled administrator must remain, so this account cannot be deleted yet. Nothing was deleted.',
    retryable: false,
  },
  DELETE_METHOD_MISMATCH: {
    title: 'Use the other way to delete',
    message: 'Reload Settings: it shows the way that fits how your account signs in. Nothing was deleted.',
    retryable: false,
  },
  INVALID_OR_EXPIRED_TOKEN: {
    title: 'This link has expired',
    message: 'Confirmation links work once and last 15 minutes. Go to Settings and ask for a new one. Nothing was deleted.',
    retryable: false,
  },
  ANALYSIS_IN_PROGRESS: {
    title: 'A repository is still being analysed',
    message: 'Wait until it finishes, then delete your account. Nothing was deleted.',
    retryable: false,
  },
};

export function describeDeleteError(error: unknown): ErrorCopy {
  const base = describeError(error);
  const entry = Object.hasOwn(DELETE_COPY, base.code) ? DELETE_COPY[base.code] : undefined;
  return entry ? { ...base, ...entry } : base;
}

/** OAuth failures arrive as /login?error=<code>. Only these codes map to copy; the raw value is never rendered. */
const OAUTH_COPY: Record<string, string> = {
  oauth_failed: 'Signing in with that provider did not work. Try again, or use your email and password.',
  oauth_email_unverified: 'That provider has not verified your email address, so Archyra cannot sign you in with it.',
  oauth_account_disabled: 'This account has been disabled. Contact the administrator.',
  oauth_cancelled: 'Sign-in was cancelled.',
};
export const OAUTH_FALLBACK = 'Signing in with that provider did not work. Try again.';

export function describeOAuthError(code: string | null): string | null {
  if (code === null) return null;
  return Object.hasOwn(OAUTH_COPY, code) ? (OAUTH_COPY[code] as string) : OAUTH_FALLBACK;
}

/**
 * A link started in Settings returns to /settings?link_error=<code>. A fixed allow-list: the raw value is never
 * rendered, and an unknown code gets the generic copy.
 */
const LINK_ERROR_COPY: Record<string, string> = {
  oauth_cancelled: 'Linking was cancelled. Nothing changed.',
  oauth_failed: 'The provider did not complete the link. Nothing changed. Try again.',
  oauth_account_disabled: 'This account has been disabled. Contact the administrator.',
  identity_already_linked: 'That account is already linked to a different Archyra account, so it cannot be linked here. Nothing changed.',
  provider_already_linked: 'This account already has a sign-in of that provider linked. Remove it first to link a different one.',
  link_expired: 'That link request expired or was not started from this browser. Start it again from here.',
};
export const LINK_ERROR_FALLBACK = 'The link did not complete. Nothing changed. Try again.';

export function describeLinkError(code: string): string {
  return Object.hasOwn(LINK_ERROR_COPY, code) ? (LINK_ERROR_COPY[code] as string) : LINK_ERROR_FALLBACK;
}
