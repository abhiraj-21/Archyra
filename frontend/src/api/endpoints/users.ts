import { http } from '../http';
import type { UsageResponse, UserProfile } from '../types';

export const getMe = (signal?: AbortSignal) => http<UserProfile>('/users/me', { signal });
export const updateMe = (name: string) => http<UserProfile>('/users/me', { method: 'PATCH', body: { name } });
export const getUsage = (signal?: AbortSignal) => http<UsageResponse>('/usage', { signal });
