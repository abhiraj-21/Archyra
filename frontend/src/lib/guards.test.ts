import { afterEach, describe, expect, it, vi } from 'vitest';
import { forgetRunsForTests, runOnce } from './once';
import { canSeeAdmin } from './roles';
import { safeNext } from './returnTo';
import { statusPollInterval } from '@/hooks/useAnalyses';
import { ApiError } from '@/api/errors';

describe('canSeeAdmin (role from /users/me only)', () => {
  it('is true only for role ADMIN', () => {
    expect(canSeeAdmin({ role: 'ADMIN' })).toBe(true);
    expect(canSeeAdmin({ role: 'USER' })).toBe(false);
    expect(canSeeAdmin(undefined)).toBe(false);
    expect(canSeeAdmin(null)).toBe(false);
  });
});

describe('runOnce (verify-email guard)', () => {
  afterEach(() => forgetRunsForTests());

  it('submits a token once however often it is asked', async () => {
    const submit = vi.fn(async () => 'verified');
    const results = await Promise.all([runOnce('verify-email:abc', submit), runOnce('verify-email:abc', submit)]);
    await runOnce('verify-email:abc', submit);
    expect(submit).toHaveBeenCalledTimes(1);
    expect(results).toEqual(['verified', 'verified']);
  });

  it('shares a failure instead of retrying a consumed token', async () => {
    const submit = vi.fn(async () => {
      throw new Error('expired');
    });
    await expect(runOnce('verify-email:x', submit)).rejects.toThrow('expired');
    await expect(runOnce('verify-email:x', submit)).rejects.toThrow('expired');
    expect(submit).toHaveBeenCalledTimes(1);
  });
});

describe('safeNext', () => {
  it.each([
    ['/analyses/1/architecture?nodes=CLASS', '/analyses/1/architecture?nodes=CLASS'],
    ['//evil.example', '/dashboard'],
    ['https://evil.example', '/dashboard'],
    ['/\\evil.example', '/dashboard'],
    ['/login', '/dashboard'],
    [null, '/dashboard'],
  ])('%s → %s', (input, expected) => expect(safeNext(input)).toBe(expected));
});

describe('statusPollInterval', () => {
  const q = (state: object) => ({ state: { fetchFailureCount: 0, status: 'success', error: null, ...state } }) as never;
  it('polls every 2s while running and stops at terminal states', () => {
    expect(statusPollInterval(q({ data: { status: 'PARSING' } }))).toBe(2000);
    expect(statusPollInterval(q({ data: { status: 'COMPLETED' } }))).toBe(false);
    expect(statusPollInterval(q({ data: { status: 'FAILED' } }))).toBe(false);
  });
  it('backs off on transient errors and stops on 404', () => {
    expect(statusPollInterval(q({ fetchFailureCount: 2, status: 'error', error: new ApiError(0, 'NETWORK_ERROR', '') }))).toBe(8000);
    expect(statusPollInterval(q({ status: 'error', error: new ApiError(404, 'ANALYSIS_NOT_FOUND', '') }))).toBe(false);
  });
});
