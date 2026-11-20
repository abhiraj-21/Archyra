/**
 * A one-shot flag: "an interactive sign-in just finished", so the Premium expiry dialog shows once per sign-in (email, Google or
 * GitHub) and never after a silent session refresh or a reload. It is set where a sign-in completes (the email `signIn` of the
 * session provider, and the OAuth callback once the session boot found the new session) and read and cleared once by the
 * dialog. Session storage holds the word only, never a token.
 */
const KEY = 'archyra.signedIn';

export function markSignedIn(): void {
  try {
    // eslint-disable-next-line no-restricted-globals
    sessionStorage.setItem(KEY, '1');
  } catch {
    // storage blocked: the dialog simply does not show; the banner still does
  }
}

/** Reads the flag without clearing it (safe in a React state initialiser, which StrictMode runs twice). */
export function peekSignedIn(): boolean {
  try {
    // eslint-disable-next-line no-restricted-globals
    return sessionStorage.getItem(KEY) === '1';
  } catch {
    return false;
  }
}

export function clearSignedIn(): void {
  try {
    // eslint-disable-next-line no-restricted-globals
    sessionStorage.removeItem(KEY);
  } catch {
    // nothing to clear
  }
}
