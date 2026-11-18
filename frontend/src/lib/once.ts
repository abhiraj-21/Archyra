/**
 * Runs an async action at most once per key for the lifetime of the page. Email-verification and similar tokens
 * are single-use: React StrictMode's double effects, re-renders and remounts must never submit one twice.
 */
const runs = new Map<string, Promise<unknown>>();

export function runOnce<T>(key: string, action: () => Promise<T>): Promise<T> {
  let run = runs.get(key) as Promise<T> | undefined;
  if (!run) {
    run = action();
    runs.set(key, run);
  }
  return run;
}

export function forgetRunsForTests(): void {
  runs.clear();
}
