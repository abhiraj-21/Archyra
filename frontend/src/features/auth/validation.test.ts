import { describe, expect, it } from 'vitest';
import { changedPasswordError } from './validation';

describe('changedPasswordError (Settings → Password)', () => {
  const email = 'ada@example.com';

  it('accepts a good password that differs from the current one', () => {
    expect(changedPasswordError('another long passphrase', 'correct horse battery', email)).toBeNull();
  });

  it('applies the usual new-password rules first', () => {
    expect(changedPasswordError('', 'old', email)).toBe('Choose a password.');
    expect(changedPasswordError('short', 'old', email)).toMatch(/at least 8/);
    expect(changedPasswordError('é'.repeat(37), 'old', email)).toMatch(/over 72 bytes/);
    expect(changedPasswordError('ADA@example.com', 'old', email)).toMatch(/email address/);
  });

  it('refuses the current password as the new one', () => {
    expect(changedPasswordError('correct horse battery', 'correct horse battery', email)).toMatch(/different from your current/);
  });
});
