import { describe, expect, it } from 'vitest';
import { ANALYSIS_STATUSES, RUNNING_STATUSES, isNotAnalysed, isRunning, isSettled, isTerminal } from '@/api/types';
import { describeError } from './errorCopy';
import { ApiError } from '@/api/errors';
import { creditReturnNote, RESTORED_NOTE } from './restoredEntry';
import { STATUS_LABEL, statusLabel } from './status';
import { statusPollInterval } from '@/hooks/useAnalyses';

describe('NOT_ANALYZED is idle', () => {
  it('is a status with a label', () => {
    expect(ANALYSIS_STATUSES).toContain('NOT_ANALYZED');
    expect(statusLabel('NOT_ANALYZED')).toBe('Not analysed');
    for (const status of ANALYSIS_STATUSES) expect(STATUS_LABEL[status]).toBeTruthy();
  });

  it('is neither running nor finished', () => {
    expect(isRunning('NOT_ANALYZED')).toBe(false);
    expect(isTerminal('NOT_ANALYZED')).toBe(false);
    expect(isNotAnalysed('NOT_ANALYZED')).toBe(true);
    expect(isSettled('NOT_ANALYZED')).toBe(true);
    expect(RUNNING_STATUSES).not.toContain('NOT_ANALYZED');
  });

  it('keeps the other classifications', () => {
    for (const status of RUNNING_STATUSES) {
      expect(isRunning(status)).toBe(true);
      expect(isSettled(status)).toBe(false);
    }
    expect(isTerminal('COMPLETED')).toBe(true);
    expect(isTerminal('FAILED')).toBe(true);
    expect(isRunning('COMPLETED')).toBe(false);
    expect(isRunning('FAILED')).toBe(false);
  });

  it('is never polled', () => {
    const query = { state: { data: { status: 'NOT_ANALYZED' }, status: 'success', fetchFailureCount: 0, error: null } } as unknown as Parameters<typeof statusPollInterval>[0];
    expect(statusPollInterval(query)).toBe(false);
    const running = { state: { data: { status: 'QUEUED' }, status: 'success', fetchFailureCount: 0, error: null } } as unknown as Parameters<typeof statusPollInterval>[0];
    expect(statusPollInterval(running)).toBe(2000);
  });
});

describe('copy', () => {
  it('says what was restored and the two ways out', () => {
    expect(RESTORED_NOTE).toBe(
      'Restored from your previous account. Analyse it to use the credit you already spent, or delete it to get the credit back.',
    );
  });

  it('reads the real usage numbers for a free user', () => {
    expect(creditReturnNote('FREE', { used: 5, limit: 5 })).toBe('Deleting it gives you back one analysis: you have used 5 of 5, and will have used 4 of 5.');
    expect(creditReturnNote('FREE', { used: 0, limit: 5 })).toContain('will have used 0 of 5');
  });

  it('does not promise a credit to Premium, and is general before the usage loads', () => {
    expect(creditReturnNote('PREMIUM', { used: 9, limit: null })).toContain('does not change your count');
    expect(creditReturnNote(undefined, undefined)).toBe('On the free plan, deleting it gives you back one free analysis.');
    expect(creditReturnNote('FREE', { used: 3, limit: null })).toContain('one free analysis');
  });

  it('maps ANALYSIS_NOT_RESTORABLE by code, not by message', () => {
    const copy = describeError(new ApiError(409, 'ANALYSIS_NOT_RESTORABLE', 'whatever the server says'));
    expect(copy.title).toBe('Already analysed or running');
    expect(copy.message).not.toContain('whatever');
    expect(copy.retryable).toBe(false);
  });
});
