import { isApiError, type ErrorCode } from '@/api/errors';

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
  AI_NOT_READY: { title: 'Ask AI is not ready', message: 'This repository is still being prepared for questions. Check its status and try again.', retryable: true },
  AI_REANALYZE_REQUIRED: { title: 'Re-analyze to enable Ask AI', message: 'This analysis cannot be prepared for questions again. Re-analyze the repository to enable Ask AI.', retryable: false },
  AI_UNAVAILABLE: { title: 'The AI service is busy', message: 'The AI service is busy right now. Your question wasn\'t counted. Try again in a minute.', retryable: true },
  CONVERSATION_NOT_FOUND: { title: 'Conversation not found', message: 'It may have been deleted. Start a new conversation.', retryable: false },
  CONVERSATION_FULL: { title: 'This conversation is full', message: 'Start a new conversation to keep asking.', retryable: false },
  CONVERSATION_BUSY: { title: 'Still answering', message: 'Wait for the current answer to finish.', retryable: true },
  TOO_MANY_CONVERSATIONS: { title: 'Too many conversations', message: 'Delete a conversation you no longer need to start another.', retryable: false },
  NETWORK_ERROR: { title: 'Cannot reach Archyra', message: 'Check your connection, then try again.', retryable: true },
  TIMEOUT: { title: 'That took too long', message: 'The server did not answer in time. Try again.', retryable: true },
  UNEXPECTED_RESPONSE: { title: 'Unreadable answer', message: 'The server sent an answer that could not be read.', retryable: true },
};

const GENERIC: Entry = { title: 'Something went wrong', message: 'The request failed. Try again in a moment.', retryable: true };

export function describeError(error: unknown): ErrorCopy {
  if (!isApiError(error)) return { code: 'UNKNOWN', ...GENERIC };
  const entry = COPY[error.code] ?? (error.status >= 500 ? GENERIC : { ...GENERIC, retryable: false });
  const message = error.code === 'VALIDATION_ERROR' && error.message ? error.message : entry.message;
  return { code: error.code, ...entry, message, retryAfter: error.retryAfter };
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
