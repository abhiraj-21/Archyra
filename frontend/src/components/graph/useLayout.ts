import { useEffect, useState } from 'react';
import type { LayoutInput, LayoutResult } from './layoutCore';

let worker: Worker | null = null;
let seq = 0;
const pending = new Map<number, (message: { result?: LayoutResult; error?: string }) => void>();
const cache = new Map<string, LayoutResult>();

function getWorker(): Worker | null {
  if (typeof Worker === 'undefined') return null;
  if (!worker) {
    worker = new Worker(new URL('./layout.worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (e: MessageEvent<{ id: number; result?: LayoutResult; error?: string }>) => {
      pending.get(e.data.id)?.(e.data);
      pending.delete(e.data.id);
    };
  }
  return worker;
}

async function runLayout(input: LayoutInput): Promise<LayoutResult> {
  const w = getWorker();
  if (!w) {
    const { computeLayout } = await import('./layoutCore');
    return computeLayout(input);
  }
  const id = ++seq;
  return new Promise((resolve, reject) => {
    pending.set(id, (r) => (r.result ? resolve(r.result) : reject(new Error(r.error ?? 'layout failed'))));
    w.postMessage({ id, input });
  });
}

/** Positions for the visible graph, computed in a worker and cached by `key` (the visible ids). */
export function useLayout(key: string, input: LayoutInput) {
  const [state, setState] = useState<{ key: string; layout: LayoutResult | null; error: string | null }>(() => ({
    key,
    layout: cache.get(key) ?? null,
    error: null,
  }));

  useEffect(() => {
    const cached = cache.get(key);
    if (cached) {
      setState({ key, layout: cached, error: null });
      return;
    }
    let alive = true;
    setState((s) => ({ key, layout: s.key === key ? s.layout : null, error: null }));
    runLayout(input).then(
      (layout) => {
        cache.set(key, layout);
        if (cache.size > 24) cache.delete(cache.keys().next().value as string);
        if (alive) setState({ key, layout, error: null });
      },
      (e: unknown) => alive && setState({ key, layout: null, error: e instanceof Error ? e.message : 'layout failed' }),
    );
    return () => {
      alive = false;
    };
    // `input` is derived from `key`; re-running on its identity would only repeat the same layout.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const layout = state.key === key ? state.layout : null;
  return { positions: layout?.positions ?? null, error: state.error, pending: state.key !== key || (!layout && !state.error) };
}
