import { http } from '../http';
import { setAccessToken } from '../session';
import type { AuthResponse, MessageResponse } from '../types';

// Public auth routes: no bearer token and never a refresh on 401.
const open = { withToken: false } as const;

export async function login(email: string, password: string): Promise<void> {
  const body = await http<AuthResponse>('/auth/login', { ...open, method: 'POST', body: { email, password } });
  setAccessToken(body.accessToken);
}
export const register = (name: string, email: string, password: string) =>
  http<MessageResponse>('/auth/register', { ...open, method: 'POST', body: { name, email, password } });

/**
 * Settings → Password. Needs the bearer token (so it is not an `open` route). The answer is a new session: every other
 * session of the user has ended, so the access token and the refresh cookie (set by this response) are replaced.
 */
export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  const body = await http<AuthResponse>('/users/me/password', { method: 'POST', body: { currentPassword, newPassword } });
  setAccessToken(body.accessToken);
}
export const verifyEmail =(token: string) => http<MessageResponse>('/auth/verify-email', { ...open, method: 'POST', body: { token } });
export const resendVerification = (email: string) =>
  http<MessageResponse>('/auth/resend-verification', { ...open, method: 'POST', body: { email } });
export const forgotPassword = (email: string) => http<MessageResponse>('/auth/forgot-password', { ...open, method: 'POST', body: { email } });
export const resetPassword = (token: string, newPassword: string) =>
  http<MessageResponse>('/auth/reset-password', { ...open, method: 'POST', body: { token, newPassword } });
