import { describe, expect, it } from 'vitest';
import { ApiError, SERVER_ERROR_CODES, CLIENT_ERROR_CODES } from '@/api/errors';
import { describeError, describeOAuthError, OAUTH_FALLBACK } from './errorCopy';

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
