import { useEffect, useRef, useState } from 'react';
import type { AnalysisStatus } from '@/api/types';

/** Watches for the running → COMPLETED transition and holds a short completion moment before navigating. */
export function useCompletionMoment(status: AnalysisStatus | undefined, onDone: () => void, delayMs = 1200) {
  const firstSeen = useRef<AnalysisStatus | undefined>(undefined);
  const [moment, setMoment] = useState(false);
  if (firstSeen.current === undefined && status) firstSeen.current = status;
  const sawRunning = firstSeen.current !== undefined && firstSeen.current !== 'COMPLETED';

  useEffect(() => {
    if (status !== 'COMPLETED') return;
    if (!sawRunning) return; // already COMPLETED on arrival: the page redirects without a moment
    setMoment(true);
    const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    const id = window.setTimeout(onDone, reduce ? 400 : delayMs);
    return () => window.clearTimeout(id);
  }, [status, sawRunning, onDone, delayMs]);

  return moment;
}
