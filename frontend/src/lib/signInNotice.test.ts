import { afterEach, describe, expect, it, vi } from 'vitest';
import { clearSignedIn, markSignedIn, peekSignedIn } from './signInNotice';

describe('the one-shot sign-in flag', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    clearSignedIn();
  });

  it('is off until a sign-in sets it, survives reading, and is gone once cleared', () => {
    expect(peekSignedIn()).toBe(false);
    markSignedIn();
    expect(peekSignedIn()).toBe(true);
    expect(peekSignedIn()).toBe(true); // a state initialiser may read twice (StrictMode)
    clearSignedIn();
    expect(peekSignedIn()).toBe(false);
  });

  it('never throws when storage is blocked: the dialog just does not show', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    expect(() => markSignedIn()).not.toThrow();
    expect(peekSignedIn()).toBe(false);
  });
});
