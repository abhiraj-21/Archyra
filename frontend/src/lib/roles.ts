import type { UserProfile } from '@/api/types';

/** The admin area exists only for a user whose GET /users/me says role ADMIN. Nothing else decides it. */
export function canSeeAdmin(me: Pick<UserProfile, 'role'> | null | undefined): boolean {
  return me?.role === 'ADMIN';
}
