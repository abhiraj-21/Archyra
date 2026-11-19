import { describe, expect, it } from 'vitest';
import { canConfirmDeletion, DELETE_WORD } from './deleteGate';

describe('canConfirmDeletion', () => {
  it('needs the exact word', () => {
    expect(canConfirmDeletion('', '', false)).toBe(false);
    expect(canConfirmDeletion('delete', '', false)).toBe(false);
    expect(canConfirmDeletion('DELETE ', '', false)).toBe(false);
    expect(canConfirmDeletion(' DELETE', '', false)).toBe(false);
    expect(canConfirmDeletion('DELET', '', false)).toBe(false);
    expect(canConfirmDeletion(DELETE_WORD, '', false)).toBe(true);
  });

  it('also needs the password for an account that has one', () => {
    expect(canConfirmDeletion(DELETE_WORD, '', true)).toBe(false);
    expect(canConfirmDeletion(DELETE_WORD, 'secret', true)).toBe(true);
    expect(canConfirmDeletion('nope', 'secret', true)).toBe(false);
  });
});
