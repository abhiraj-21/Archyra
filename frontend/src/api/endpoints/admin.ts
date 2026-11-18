import { http } from '../http';
import type { AdminStats, AdminUser, Page } from '../types';

export const getAdminStats = (signal?: AbortSignal) => http<AdminStats>('/admin/stats', { signal });
export const listAdminUsers = (page: number, size: number, signal?: AbortSignal) =>
  http<Page<AdminUser>>('/admin/users', { query: { page, size }, signal });
export const getAdminUser = (userId: string, signal?: AbortSignal) =>
  http<AdminUser>(`/admin/users/${encodeURIComponent(userId)}`, { signal });
export const setAdminUserStatus = (userId: string, enabled: boolean) =>
  http<AdminUser>(`/admin/users/${encodeURIComponent(userId)}/status`, { method: 'PATCH', body: { enabled } });
