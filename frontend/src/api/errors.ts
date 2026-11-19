/**
 * Every failure of a backend call becomes an ApiError with a machine-readable `code`. The UI decides what to do
 * from `code` alone (see src/lib/errorCopy.ts), never from `message` text.
 */

/** Codes the backend emits (GlobalExceptionHandler, the security entry points, the refresh endpoint). */
export const SERVER_ERROR_CODES = [
  'AUTHENTICATION_REQUIRED',
  'ACCESS_DENIED',
  'CSRF_TOKEN_INVALID',
  'REFRESH_TOKEN_INVALID',
  'INVALID_CREDENTIALS',
  'EMAIL_NOT_VERIFIED',
  'ACCOUNT_DISABLED',
  'INVALID_OR_EXPIRED_TOKEN',
  'PREMIUM_FEATURE',
  'USAGE_LIMIT_EXCEEDED',
  'RATE_LIMITED',
  'SERVICE_BUSY',
  'VALIDATION_ERROR',
  'INVALID_REQUEST_BODY',
  'ANALYSIS_NOT_FOUND',
  'NODE_NOT_FOUND',
  'USER_NOT_FOUND',
  'ANALYSIS_NOT_COMPLETED',
  'ANALYSIS_IN_PROGRESS',
  'CANNOT_DISABLE_SELF',
  'LAST_ADMIN',
  'PROVIDER_ALREADY_LINKED',
  'LAST_LOGIN_METHOD',
  'IDENTITY_NOT_FOUND',
  'INCORRECT_PASSWORD',
  'NO_PASSWORD_SET',
] as const;

/** Codes the client assigns when no backend answer could be read. */
export const CLIENT_ERROR_CODES = ['NETWORK_ERROR', 'TIMEOUT', 'UNEXPECTED_RESPONSE'] as const;

export type ServerErrorCode = (typeof SERVER_ERROR_CODES)[number];
export type ClientErrorCode = (typeof CLIENT_ERROR_CODES)[number];
/** A string, because an unknown code from a newer backend must still flow through to the generic message. */
export type ErrorCode = ServerErrorCode | ClientErrorCode | (string & {});

export class ApiError extends Error {
  readonly status: number;
  readonly code: ErrorCode;
  /** Seconds, from the Retry-After header (RATE_LIMITED). */
  readonly retryAfter: number | undefined;

  constructor(status: number, code: ErrorCode, message: string, retryAfter?: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.retryAfter = retryAfter;
  }
}

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

export function hasCode(value: unknown, ...codes: ErrorCode[]): value is ApiError {
  return isApiError(value) && codes.includes(value.code);
}

function parseRetryAfter(header: string | null): number | undefined {
  if (!header) return undefined;
  const seconds = Number(header);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.ceil(seconds);
  const date = Date.parse(header);
  if (Number.isNaN(date)) return undefined;
  return Math.max(0, Math.ceil((date - Date.now()) / 1000));
}

/** Reads a failed response into an ApiError. Never throws. */
export async function errorFromResponse(response: Response): Promise<ApiError> {
  const retryAfter = parseRetryAfter(response.headers.get('Retry-After'));
  let code: ErrorCode = `HTTP_${response.status}`;
  let message = response.statusText || 'The request failed.';
  try {
    const body: unknown = await response.json();
    if (body && typeof body === 'object') {
      const record = body as Record<string, unknown>;
      if (typeof record.code === 'string' && record.code) code = record.code;
      if (typeof record.message === 'string' && record.message) message = record.message;
    }
  } catch {
    // Not JSON (a proxy error page, an empty body): keep the HTTP_<status> code.
  }
  return new ApiError(response.status, code, message, retryAfter);
}

export function networkError(): ApiError {
  return new ApiError(0, 'NETWORK_ERROR', 'The server could not be reached.');
}

export function timeoutError(): ApiError {
  return new ApiError(0, 'TIMEOUT', 'The server took too long to answer.');
}

/** Transient failures worth retrying automatically: no answer, a timeout, a 5xx (SERVICE_BUSY included). */
export function isTransient(error: unknown): boolean {
  if (!isApiError(error)) return false;
  return error.status === 0 || error.status >= 500;
}
