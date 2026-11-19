import { useOutletContext } from 'react-router';

export interface WorkspaceContext {
  analysisId: string;
  /** The repository URL when known (history cache), else the repository name; only for display and the re-analyze dialog. */
  repositoryUrl: string;
  /** The analysis is COMPLETED: repository results may be requested. */
  ready: true;
}

export const useWorkspace = () => useOutletContext<WorkspaceContext>();
