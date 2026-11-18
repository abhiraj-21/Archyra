/**
 * The access token lives in this module's memory only: never in localStorage, sessionStorage or a cookie written by
 * JavaScript, never in a URL, never logged. A reload loses it on purpose; the boot sequence gets a new one from the
 * HttpOnly refresh cookie.
 */
let accessToken: string | null = null;
let expiredHandler: (() => void) | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string): void {
  accessToken = token;
}

export function clearAccessToken(): void {
  accessToken = null;
}

/** The session provider registers what "the session is over" means for the UI (clear cache, go to /login). */
export function onSessionExpired(handler: (() => void) | null): void {
  expiredHandler = handler;
}

/** Called when a refresh fails: the user is signed out from now on. */
export function expireSession(): void {
  accessToken = null;
  expiredHandler?.();
}
