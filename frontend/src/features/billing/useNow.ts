import { useEffect, useState } from 'react';

/** The current time, refreshed every `everyMs` (default a minute), so "ends today at 05:30" stays true on an open page. */
export function useNow(everyMs = 60_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), everyMs);
    return () => clearInterval(timer);
  }, [everyMs]);
  return now;
}
