import type { AnalysisStatus } from '@/api/types';

export const STATUS_LABEL: Record<AnalysisStatus, string> = {
  QUEUED: 'Queued',
  CLONING: 'Cloning',
  PARSING: 'Parsing',
  ANALYZING: 'Analysing',
  BUILDING_GRAPH: 'Building graph',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
};

export function statusLabel(status: AnalysisStatus): string {
  return STATUS_LABEL[status];
}

