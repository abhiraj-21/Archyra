import { http } from '../http';
import type { AiIndexResponse, ChatAnswer, ChatMessage, ChatQuestion, ConversationSummary, Page } from '../types';

const a = (analysisId: string) => `/analyses/${encodeURIComponent(analysisId)}`;

/** A question may take a while (retrieval plus a model, with a fallback): the client gives up at about 75 s. */
export const ASK_TIMEOUT_MS = 75_000;

export const getAiIndex = (analysisId: string, signal?: AbortSignal) => http<AiIndexResponse>(`${a(analysisId)}/ai-index`, { signal });
export const retryAiIndex = (analysisId: string) => http<AiIndexResponse>(`${a(analysisId)}/ai-index`, { method: 'POST' });

export const listConversations = (analysisId: string, page: number, size: number, signal?: AbortSignal) =>
  http<Page<ConversationSummary>>(`${a(analysisId)}/conversations`, { query: { page, size }, signal });
export const createConversation = (analysisId: string) => http<ConversationSummary>(`${a(analysisId)}/conversations`, { method: 'POST' });
export const deleteConversation = (conversationId: string) => http<void>(`/conversations/${encodeURIComponent(conversationId)}`, { method: 'DELETE' });
export const listMessages = (conversationId: string, page: number, size: number, signal?: AbortSignal) =>
  http<Page<ChatMessage>>(`/conversations/${encodeURIComponent(conversationId)}/messages`, { query: { page, size }, signal });

export const askQuestion = (analysisId: string, question: ChatQuestion, signal?: AbortSignal) =>
  http<ChatAnswer>(`${a(analysisId)}/chat`, { method: 'POST', body: question, signal, timeoutMs: ASK_TIMEOUT_MS });
