import { byteLength } from '@/lib/format';

/** Client-side hints only; the backend's PasswordPolicy and Jakarta validation decide. */
export const PASSWORD_MIN = 8;
export const PASSWORD_MAX_BYTES = 72;
export const PASSWORD_HINT = `8 to 72 characters. Accented letters and symbols count as more than one, up to 72 bytes in all.`;

export function emailError(value: string): string | null {
  const v = value.trim();
  if (!v) return 'Enter your email address.';
  if (v.length > 254) return 'That email address is too long.';
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v)) return 'That email address looks incomplete. Write it as name@example.com.';
  return null;
}

export function newPasswordError(value: string, email?: string): string | null {
  if (!value) return 'Choose a password.';
  if (value.length < PASSWORD_MIN) return `Use at least ${PASSWORD_MIN} characters.`;
  if (byteLength(value) > PASSWORD_MAX_BYTES) return `That password is over ${PASSWORD_MAX_BYTES} bytes. Use a shorter one.`;
  if (email && value.trim().toLowerCase() === email.trim().toLowerCase()) return 'Your password cannot be your email address.';
  return null;
}

export function nameError(value: string): string | null {
  const v = value.trim();
  if (!v) return 'Enter your name.';
  if (v.length > 100) return 'Use 100 characters or fewer.';
  if ([...v].some((c) => c.charCodeAt(0) < 32 || c.charCodeAt(0) === 127)) return 'Remove the control characters.';
  return null;
}
