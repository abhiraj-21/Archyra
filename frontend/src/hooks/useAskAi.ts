import { useMutation, useQuery, useQueryClient, type Query } from '@tanstack/react-query';
import { askQuestion, createConversation, deleteConversation, getAiIndex, listConversations, listMessages, retryAiIndex } from '@/api/endpoints/ai';
import { queryKeys } from '@/api/queryKeys';
import type { AiIndexResponse, ChatAnswer } from '@/api/types';

const INDEX_POLL_MS = 2000;
/** The backend keeps at most 50 conversations per analysis and 100 messages per conversation: one page of each is everything. */
export const CONVERSATION_PAGE_SIZE = 50;
export const MESSAGE_PAGE_SIZE = 100;

/** Poll while INDEXING only: READY, FAILED, EMPTY and NOT_INDEXED are final, and a hidden tab never polls. */
export function indexPollInterval(query: Pick<Query<AiIndexResponse>, 'state'>): number | false {
  return query.state.data?.status === 'INDEXING' ? INDEX_POLL_MS : false;
}

/** The Ask AI index of an analysis. Idle until the analysis is COMPLETED (`ready`). */
export function useAiIndex(analysisId: string, ready: boolean) {
  return useQuery({
    queryKey: queryKeys.aiIndex(analysisId),
    queryFn: ({ signal }) => getAiIndex(analysisId, signal),
    enabled: ready,
    refetchInterval: indexPollInterval,
    refetchIntervalInBackground: false,
  });
}

/** A free retry of a FAILED index; the answer is the new state (INDEXING), so polling resumes at once. */
export function useRetryAiIndex(analysisId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => retryAiIndex(analysisId),
    onSuccess: (state) => queryClient.setQueryData(queryKeys.aiIndex(analysisId), state),
    onError: () => queryClient.invalidateQueries({ queryKey: queryKeys.aiIndex(analysisId) }),
  });
}

export function useConversations(analysisId: string, enabled: boolean) {
  return useQuery({
    queryKey: queryKeys.conversationsPage(analysisId, 0, CONVERSATION_PAGE_SIZE),
    queryFn: ({ signal }) => listConversations(analysisId, 0, CONVERSATION_PAGE_SIZE, signal),
    enabled,
  });
}

export function useCreateConversation(analysisId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => createConversation(analysisId),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.conversations(analysisId) }),
  });
}

export function useDeleteConversation(analysisId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (conversationId: string) => deleteConversation(conversationId),
    onSuccess: (_void, conversationId) => queryClient.removeQueries({ queryKey: queryKeys.conversationMessages(conversationId) }),
    onSettled: () => queryClient.invalidateQueries({ queryKey: queryKeys.conversations(analysisId) }),
  });
}

/** The messages of one conversation, oldest first. Idle without a conversation. */
export function useConversationMessages(conversationId: string | null) {
  return useQuery({
    queryKey: queryKeys.conversationMessages(conversationId ?? ''),
    queryFn: ({ signal }) => listMessages(conversationId as string, 0, MESSAGE_PAGE_SIZE, signal),
    enabled: conversationId !== null,
  });
}

export interface AskVariables {
  message: string;
  conversationId?: string;
}

/**
 * Asks one question. The mutation stays pending until the conversation's messages and the usage are refreshed, so the
 * thread never flickers between the optimistic turn and the stored one. Failures leave everything untouched: the caller
 * keeps the typed text.
 */
export function useAskQuestion(analysisId: string) {
  const queryClient = useQueryClient();
  return useMutation<ChatAnswer, Error, AskVariables>({
    mutationFn: (variables) => askQuestion(analysisId, variables.conversationId ? { message: variables.message, conversationId: variables.conversationId } : { message: variables.message }),
    onSuccess: async (answer) => {
      await Promise.all([
        queryClient.fetchQuery({
          queryKey: queryKeys.conversationMessages(answer.conversationId),
          queryFn: ({ signal }) => listMessages(answer.conversationId, 0, MESSAGE_PAGE_SIZE, signal),
          staleTime: 0,
        }),
        queryClient.invalidateQueries({ queryKey: queryKeys.conversations(analysisId) }),
        queryClient.invalidateQueries({ queryKey: queryKeys.usage }),
      ]);
    },
    onError: () => {
      // A refused question may have changed what the screen should show (index state, quota, a deleted conversation).
      void queryClient.invalidateQueries({ queryKey: queryKeys.usage });
    },
  });
}
