import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getUsage, updateMe } from '@/api/endpoints/users';
import { queryKeys } from '@/api/queryKeys';
import { useSession } from './sessionContext';

export function useUsage() {
  const { status } = useSession();
  return useQuery({ queryKey: queryKeys.usage, queryFn: ({ signal }) => getUsage(signal), enabled: status === 'authenticated' });
}

/** The REPOSITORY_ANALYSIS entry of /usage, the only one consumed today. */
export function useAnalysisUsage() {
  const usage = useUsage();
  const entry = usage.data?.usage.find((u) => u.usageType === 'REPOSITORY_ANALYSIS');
  return { ...usage, entry, plan: usage.data?.plan };
}

export function useUpdateName() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => updateMe(name),
    onSuccess: (profile) => queryClient.setQueryData(queryKeys.me, profile),
  });
}
