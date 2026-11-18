import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getAdminStats, getAdminUser, listAdminUsers, setAdminUserStatus } from '@/api/endpoints/admin';
import { queryKeys } from '@/api/queryKeys';
import { useSession } from './sessionContext';

export const ADMIN_PAGE_SIZE = 50;

/** Admin queries run only for a user whose /users/me role is ADMIN; the backend enforces it regardless. */
export function useAdminStats() {
  const { isAdmin } = useSession();
  return useQuery({ queryKey: queryKeys.adminStats, queryFn: ({ signal }) => getAdminStats(signal), enabled: isAdmin });
}

export function useAdminUsers(page: number) {
  const { isAdmin } = useSession();
  return useQuery({
    queryKey: queryKeys.adminUsers(page, ADMIN_PAGE_SIZE),
    queryFn: ({ signal }) => listAdminUsers(page, ADMIN_PAGE_SIZE, signal),
    enabled: isAdmin,
    placeholderData: keepPreviousData,
  });
}

export function useAdminUser(userId: string) {
  const { isAdmin } = useSession();
  return useQuery({ queryKey: queryKeys.adminUser(userId), queryFn: ({ signal }) => getAdminUser(userId, signal), enabled: isAdmin });
}

export function useSetUserEnabled(userId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (enabled: boolean) => setAdminUserStatus(userId, enabled),
    onSuccess: (user) => {
      queryClient.setQueryData(queryKeys.adminUser(userId), user);
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin });
    },
  });
}
