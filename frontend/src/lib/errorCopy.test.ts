import { describe, expect, it } from 'vitest';
import { ApiError, SERVER_ERROR_CODES, CLIENT_ERROR_CODES } from '@/api/errors';
import { describeDeleteError, describeError, describeOAuthError, OAUTH_FALLBACK } from './errorCopy';

describe('Ask AI error copy', () => {
  it('says what happened and what to do for every Ask AI code', () => {
    for (const code of ['AI_NOT_READY', 'AI_REANALYZE_REQUIRED', 'AI_UNAVAILABLE', 'CONVERSATION_NOT_FOUND', 'CONVERSATION_FULL', 'CONVERSATION_BUSY', 'TOO_MANY_CONVERSATIONS']) {
      const copy = describeError(new ApiError(409, code, 'server text'));
      expect(copy.title, code).not.toBe('Something went wrong');
      expect(copy.message, code).not.toContain('server text');
    }
  });

  it('tells the user an unavailable AI did not count their question', () => {
    expect(describeError(new ApiError(503, 'AI_UNAVAILABLE', 'x'))).toMatchObject({ retryable: true, message: expect.stringContaining('wasn\'t counted') });
  });
});

describe('describeError', () => {
  it('has specific copy for every code the backend and client emit', () => {
    for (const code of [...SERVER_ERROR_CODES, ...CLIENT_ERROR_CODES]) {
      const copy = describeError(new ApiError(400, code, 'server text'));
      expect(copy.title, code).not.toBe('Something went wrong');
    }
  });

  it('falls back to a generic message for an unknown code', () => {
    const copy = describeError(new ApiError(418, 'BRAND_NEW_CODE', 'server text'));
    expect(copy).toMatchObject({ code: 'BRAND_NEW_CODE', title: 'Something went wrong', retryable: false });
    expect(copy.message).not.toContain('server text');
  });

  it('treats an unknown 5xx as retryable and a non-ApiError as generic', () => {
    expect(describeError(new ApiError(500, 'HTTP_500', ''))).toMatchObject({ retryable: true });
    expect(describeError(new Error('boom'))).toMatchObject({ code: 'UNKNOWN', retryable: true });
  });

  it('shows the server message only for VALIDATION_ERROR (it lists allowed values)', () => {
    expect(describeError(new ApiError(400, 'VALIDATION_ERROR', 'Allowed values: A, B')).message).toBe('Allowed values: A, B');
    expect(describeError(new ApiError(401, 'INVALID_CREDENTIALS', 'raw')).message).not.toBe('raw');
  });

  it('carries Retry-After through', () => {
    expect(describeError(new ApiError(429, 'RATE_LIMITED', 'x', 30))).toMatchObject({ retryAfter: 30, retryable: true });
  });
});

describe('describeOAuthError', () => {
  it('maps known codes and never echoes an unknown value', () => {
    expect(describeOAuthError('oauth_cancelled')).toBe('Sign-in was cancelled.');
    expect(describeOAuthError('<script>alert(1)</script>')).toBe(OAUTH_FALLBACK);
    expect(describeOAuthError('constructor')).toBe(OAUTH_FALLBACK);
    expect(describeOAuthError(null)).toBeNull();
  });
});

describe('describeDeleteError', () => {
  it('says a wrong password is a wrong password, not a failed sign-in', () => {
    const copy = describeDeleteError(new ApiError(401, 'INVALID_CREDENTIALS', 'Invalid email or password'));
    expect(copy).toMatchObject({ code: 'INVALID_CREDENTIALS', title: 'Wrong password', retryable: false });
    expect(copy.message).not.toContain('email');
    expect(describeError(new ApiError(401, 'INVALID_CREDENTIALS', 'x')).title).toBe('Sign-in failed');
  });

  it('has delete-specific copy that says nothing was deleted, for every refusal the backend can send', () => {
    for (const code of ['INVALID_CREDENTIALS', 'LAST_ADMIN', 'DELETE_METHOD_MISMATCH', 'INVALID_OR_EXPIRED_TOKEN', 'ANALYSIS_IN_PROGRESS']) {
      const copy = describeDeleteError(new ApiError(code === 'LAST_ADMIN' ? 409 : 400, code, 'server text'));
      expect(copy.title, code).not.toBe('Something went wrong');
      expect(copy.message, code).toContain('Nothing was deleted');
      expect(copy.message, code).not.toContain('server text');
    }
  });

  it('points an expired link to Settings for a new one', () => {
    expect(describeDeleteError(new ApiError(400, 'INVALID_OR_EXPIRED_TOKEN', 'x'))).toMatchObject({
      title: 'This link has expired',
      message: expect.stringContaining('Settings'),
    });
  });

  it('keeps the ordinary copy and retry advice for everything else', () => {
    expect(describeDeleteError(new ApiError(429, 'RATE_LIMITED', 'x', 30))).toMatchObject({ retryable: true, retryAfter: 30 });
    expect(describeDeleteError(new ApiError(0, 'NETWORK_ERROR', 'x'))).toMatchObject({ retryable: true });
    expect(describeDeleteError(new Error('boom'))).toMatchObject({ code: 'UNKNOWN' });
  });

  it('has copy for DELETE_METHOD_MISMATCH in the general table too', () => {
    expect(describeError(new ApiError(400, 'DELETE_METHOD_MISMATCH', 'x')).title).not.toBe('Something went wrong');
  });
});
