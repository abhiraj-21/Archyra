import { http } from '../http';
import { encodeNodeId } from '@/lib/encode';
import type {
  AnalysisStatusResponse,
  AnalysisSummary,
  ClassResponse,
  CreateAnalysisResponse,
  FileResponse,
  FileSummary,
  FilesQuery,
  GraphQuery,
  GraphResponse,
  NodeResponse,
  Page,
  RepositoryResponse,
} from '../types';

const a = (analysisId: string) => `/analyses/${encodeURIComponent(analysisId)}`;

export const createAnalysis = (repositoryUrl: string) =>
  http<CreateAnalysisResponse>('/analyses', { method: 'POST', body: { repositoryUrl } });
export const listAnalyses = (page: number, size: number, signal?: AbortSignal) =>
  http<Page<AnalysisSummary>>('/analyses', { query: { page, size }, signal });
export const getAnalysisStatus = (analysisId: string, signal?: AbortSignal) => http<AnalysisStatusResponse>(a(analysisId), { signal });
export const deleteAnalysis = (analysisId: string) => http<void>(a(analysisId), { method: 'DELETE' });
/** Analyses a restored (NOT_ANALYZED) entry with the credit already spent: the same row runs, nothing is charged. */
export const analyzeRestored = (analysisId: string) => http<CreateAnalysisResponse>(`${a(analysisId)}/analyze`, { method: 'POST' });
export const reanalyze = (analysisId: string) => http<CreateAnalysisResponse>(`${a(analysisId)}/reanalyze`, { method: 'POST' });

export const getRepository = (analysisId: string, signal?: AbortSignal) => http<RepositoryResponse>(`${a(analysisId)}/repository`, { signal });

/**
 * V0.1 sends only `nodeType` and `relationshipType`. The other GraphQuery fields are never sent: the backend does
 * not implement them yet, and any of them makes a FREE request a 403 PREMIUM_FEATURE.
 */
export const getGraph = (analysisId: string, query: GraphQuery, signal?: AbortSignal) =>
  http<GraphResponse>(`${a(analysisId)}/graph`, {
    query: { nodeType: query.nodeType, relationshipType: query.relationshipType },
    signal,
    timeoutMs: 60_000,
  });
export const getNode = (analysisId: string, nodeId: string, signal?: AbortSignal) =>
  http<NodeResponse>(`${a(analysisId)}/nodes/${encodeNodeId(nodeId)}`, { signal });
export const listFiles = (analysisId: string, query: FilesQuery, signal?: AbortSignal) =>
  http<Page<FileSummary>>(`${a(analysisId)}/files`, { query: { ...query }, signal });
export const getFile = (analysisId: string, fileId: string, signal?: AbortSignal) =>
  http<FileResponse>(`${a(analysisId)}/files/${encodeNodeId(fileId)}`, { signal });
export const getClass = (analysisId: string, classId: string, signal?: AbortSignal) =>
  http<ClassResponse>(`${a(analysisId)}/classes/${encodeNodeId(classId)}`, { signal });
