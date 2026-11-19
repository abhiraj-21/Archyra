import { http } from '../http';
import type { MessageResponse, UsageResponse, UserProfile } from '../types';

export const getMe = (signal?: AbortSignal) => http<UserProfile>('/users/me', { signal });
export const updateMe = (name: string) => http<UserProfile>('/users/me', { method: 'PATCH', body: { name } });
export const getUsage = (signal?: AbortSignal) => http<UsageResponse>('/usage', { signal });

/**
 * Settings → Danger zone. An account with a password sends it; an account without one asks for an emailed link and
 * confirms with its token. All three need the bearer token. Success is a 204 that also clears the refresh cookie, so
 * the caller must end the local session (`useDeleteAccount` and its two siblings do).
 */
export const deleteAccount = (password: string) => http<void>('/users/me/delete', { method: 'POST', body: { password } });
export const requestAccountDeletion = () => http<MessageResponse>('/users/me/delete-request', { method: 'POST' });
export const confirmAccountDeletion = (token: string) => http<void>('/users/me/delete-confirm', { method: 'POST', body: { token } });
