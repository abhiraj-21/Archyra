import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getLinkedIdentities, startLink, unlinkIdentity } from '@/api/endpoints/linkedIdentities';
import { queryKeys } from '@/api/queryKeys';
import type { LinkProvider } from '@/api/types';
import { useSession } from './sessionContext';

export function useLinkedIdentities() {
  const { status } = useSession();
  return useQuery({
    queryKey: queryKeys.linkedIdentities,
    queryFn: ({ signal }) => getLinkedIdentities(signal),
    enabled: status === 'authenticated',
  });
}

/** Asks the backend for a link intent. The caller navigates to `authorizationUrl` (a full page load, not fetch). */
export function useStartLink() {
  return useMutation({ mutationFn: (provider: LinkProvider) => startLink(provider) });
}

/** Removes a linked provider, then refreshes the list and /users/me (its `linkedProviders`). */
export function useUnlinkIdentity() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (provider: LinkProvider) => unlinkIdentity(provider),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.linkedIdentities }),
        queryClient.invalidateQueries({ queryKey: queryKeys.me }),
      ]),
  });
}

/** After a return from the provider: the identities and `me` may both have changed. */
export function useRefreshIdentities() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.linkedIdentities }),
      queryClient.invalidateQueries({ queryKey: queryKeys.me }),
    ]);
}
