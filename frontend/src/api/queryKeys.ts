import type { FilesQuery, GraphQuery } from './types';

/**
 * Every query key in one place. Keys under ['analysis', id] belong to one analysis and are dropped together.
 * The graph key carries every /graph parameter, present or not, so V2/V3 parameters slot in without new keys.
 */
export function graphKeyParams(query: GraphQuery) {
  return {
    nodeType: query.nodeType ?? null,
    relationshipType: query.relationshipType ?? null,
    level: query.level ?? null,
    rootNodeId: query.rootNodeId ?? null,
    depth: query.depth ?? null,
    nodeTypes: query.nodeTypes ? [...query.nodeTypes].sort() : null,
    relationshipTypes: query.relationshipTypes ? [...query.relationshipTypes].sort() : null,
    direction: query.direction ?? null,
    maxNodes: query.maxNodes ?? null,
  };
}

export const queryKeys = {
  me: ['me'] as const,
  usage: ['usage'] as const,
  analyses: ['analyses'] as const,
  analysesPage: (page: number, size: number) => ['analyses', { page, size }] as const,
  analysis: (analysisId: string) => ['analysis', analysisId] as const,
  analysisStatus: (analysisId: string) => ['analysis', analysisId, 'status'] as const,
  repository: (analysisId: string) => ['analysis', analysisId, 'repository'] as const,
  graph: (analysisId: string, query: GraphQuery) => ['analysis', analysisId, 'graph', graphKeyParams(query)] as const,
  node: (analysisId: string, nodeId: string) => ['analysis', analysisId, 'node', nodeId] as const,
  files: (analysisId: string, query: FilesQuery) =>
    ['analysis', analysisId, 'files', { language: query.language ?? null, path: query.path ?? null, page: query.page, size: query.size }] as const,
  file: (analysisId: string, fileId: string) => ['analysis', analysisId, 'file', fileId] as const,
  classDetail: (analysisId: string, classId: string) => ['analysis', analysisId, 'class', classId] as const,
  admin: ['admin'] as const,
  adminStats: ['admin', 'stats'] as const,
  adminUsers: (page: number, size: number) => ['admin', 'users', { page, size }] as const,
  adminUser: (userId: string) => ['admin', 'user', userId] as const,
};
