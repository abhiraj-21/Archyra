import { useOutletContext } from 'react-router';

export interface WorkspaceContext {
  analysisId: string;
  /** The analysis is COMPLETED: repository results may be requested. */
  ready: true;
}

export const useWorkspace = () => useOutletContext<WorkspaceContext>();
