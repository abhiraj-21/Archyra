import { QueryClient } from '@tanstack/react-query';
import { isTransient } from '@/api/errors';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        refetchOnWindowFocus: false,
        // Only transient failures are retried; a 4xx answer is a decision, not a hiccup.
        retry: (failureCount, error) => isTransient(error) && failureCount < 2,
        retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 8000),
      },
      mutations: { retry: false },
    },
  });
}
