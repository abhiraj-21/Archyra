import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { changePassword } from '@/api/endpoints/auth';
import { confirmAccountDeletion, deleteAccount, getUsage, requestAccountDeletion, updateMe } from '@/api/endpoints/users';
import { queryKeys } from '@/api/queryKeys';
import { useSession } from './sessionContext';

/** Router state the landing page reads to say the account was deleted. */
export const ACCOUNT_DELETED_STATE = { accountDeleted: true } as const;

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

/** The AI_QUERY entry of /usage: FREE has a limit, PREMIUM has null. */
export function useAiUsage() {
  const usage = useUsage();
  const entry = usage.data?.usage.find((u) => u.usageType === 'AI_QUERY');
  return { ...usage, entry, plan: usage.data?.plan };
}

/** Changes the password; on success the session is replaced by the one this call returns (see `changePassword`). */
export function useChangePassword() {
  return useMutation({ mutationFn: (v: { currentPassword: string; newPassword: string }) => changePassword(v.currentPassword, v.newPassword) });
}

export function useUpdateName() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => updateMe(name),
    onSuccess: (profile) => queryClient.setQueryData(queryKeys.me, profile),
  });
}

/**
 * After a successful deletion: end the local session through the session provider (it clears the cache and the token,
 * then every guarded page unmounts) and go to the landing page with a short notice, in the same tick. Nothing refetches
 * on a cleared cache because the observers unmount in that same update (the rule in frontend/CLAUDE.md).
 */
function useEndSessionAfterDeletion() {
  const { endAfterDeletion } = useSession();
  const navigate = useNavigate();
  return () => {
    endAfterDeletion();
    navigate('/', { replace: true, state: ACCOUNT_DELETED_STATE });
  };
}

/** Deletes the account of a user with a password. */
export function useDeleteAccount() {
  const finish = useEndSessionAfterDeletion();
  return useMutation({ mutationFn: (password: string) => deleteAccount(password), onSuccess: finish });
}

/** Account without a password: asks the server to email the confirmation link. */
export function useRequestAccountDeletion() {
  return useMutation({ mutationFn: () => requestAccountDeletion() });
}

/** Account without a password: deletes it with the token from the emailed link. */
export function useConfirmAccountDeletion() {
  const finish = useEndSessionAfterDeletion();
  return useMutation({ mutationFn: (token: string) => confirmAccountDeletion(token), onSuccess: finish });
}
