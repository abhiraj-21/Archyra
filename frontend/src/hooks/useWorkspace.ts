import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { getClass, getFile, getGraph, getNode, getRepository, listFiles } from '@/api/endpoints/analyses';
import { queryKeys } from '@/api/queryKeys';
import type { FilesQuery, GraphQuery } from '@/api/types';

/**
 * Repository results exist only for a COMPLETED analysis; every hook here takes `ready` (status === COMPLETED) and
 * stays idle until it is true.
 */
export function useRepository(analysisId: string, ready: boolean) {
  return useQuery({
    queryKey: queryKeys.repository(analysisId),
    queryFn: ({ signal }) => getRepository(analysisId, signal),
    enabled: ready,
    staleTime: Infinity,
  });
}

export function useGraph(analysisId: string, ready: boolean, query: GraphQuery = {}) {
  return useQuery({
    queryKey: queryKeys.graph(analysisId, query),
    queryFn: ({ signal }) => getGraph(analysisId, query, signal),
    enabled: ready,
    // A completed analysis never changes; a re-run gets a new id.
    staleTime: Infinity,
    gcTime: 10 * 60_000,
  });
}

export function useNode(analysisId: string, nodeId: string | null, ready: boolean) {
  return useQuery({
    queryKey: queryKeys.node(analysisId, nodeId ?? ''),
    queryFn: ({ signal }) => getNode(analysisId, nodeId as string, signal),
    enabled: ready && !!nodeId,
    staleTime: Infinity,
  });
}

export function useFiles(analysisId: string, ready: boolean, query: FilesQuery) {
  return useQuery({
    queryKey: queryKeys.files(analysisId, query),
    queryFn: ({ signal }) => listFiles(analysisId, query, signal),
    enabled: ready,
    staleTime: Infinity,
    placeholderData: keepPreviousData,
  });
}

export function useFile(analysisId: string, fileId: string | null, ready: boolean) {
  return useQuery({
    queryKey: queryKeys.file(analysisId, fileId ?? ''),
    queryFn: ({ signal }) => getFile(analysisId, fileId as string, signal),
    enabled: ready && !!fileId,
    staleTime: Infinity,
  });
}

export function useClassDetail(analysisId: string, classId: string | null, ready: boolean) {
  return useQuery({
    queryKey: queryKeys.classDetail(analysisId, classId ?? ''),
    queryFn: ({ signal }) => getClass(analysisId, classId as string, signal),
    enabled: ready && !!classId,
    staleTime: Infinity,
  });
}
