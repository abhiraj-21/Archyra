import { useCallback, useEffect, useSyncExternalStore } from 'react';
import type { Positions } from './layoutCore';
import { LAYOUT_VERSION } from './layoutVersion';

/**
 * Node positions the user dragged, laid over the automatic (dagre) layout. Kept in memory for the session only:
 * never written to storage or the backend, dropped on reload, and dropped when another analysis is opened. The store key
 * also carries the layout version, so positions placed under an older orientation are never laid over a newer layout.
 */

/** The store key of one analysis under one layout version. */
export function storeKey(analysisId: string, version: number = LAYOUT_VERSION): string {
  return `v${version}|${analysisId}`;
}

/**
 * The positions to draw: for every visible node its manual position when it has one, otherwise the automatic one.
 * Pure. Manual positions of nodes that are currently hidden are not part of the result (the caller keeps them, so a
 * node that comes back reappears where the user put it); nodes the layout does not know are left out.
 */
export function overlayPositions(auto: Positions, manual: Positions, visibleIds: Iterable<string>): Positions {
  const out: Positions = {};
  for (const id of visibleIds) {
    const p = manual[id] ?? auto[id];
    if (p) out[id] = p;
  }
  return out;
}

/** How many of the visible nodes have been moved by hand. */
export function movedCount(manual: Positions, visibleIds: Iterable<string>): number {
  let n = 0;
  for (const id of visibleIds) if (manual[id]) n += 1;
  return n;
}

/** The manual positions after `moves`; the same object when nothing actually changed. */
export function applyMoves(manual: Positions, moves: Positions): Positions {
  let next: Positions | null = null;
  for (const [id, p] of Object.entries(moves)) {
    const old = manual[id];
    if (old && old.x === p.x && old.y === p.y) continue;
    next ??= { ...manual };
    next[id] = { x: p.x, y: p.y };
  }
  return next ?? manual;
}

// ---- the session store: one analysis at a time -------------------------------------------------------------------

const EMPTY: Positions = Object.freeze({}) as Positions;
type Entry = { key: string; positions: Positions };
let current: Entry | null = null;

/** The positions an entry holds for `key`; nothing when it was recorded for another analysis or another layout version. Pure. */
export function positionsFor(entry: Entry | null, key: string): Positions {
  return entry?.key === key ? entry.positions : EMPTY;
}
const listeners = new Set<() => void>();

const notify = () => listeners.forEach((l) => l());
const subscribe = (l: () => void) => {
  listeners.add(l);
  return () => listeners.delete(l);
};

/** Manual positions of one analysis, with `move` (remember the final positions of moved nodes) and `reset`. */
export function useManualPositions(analysisId: string) {
  const key = storeKey(analysisId);
  const manual = useSyncExternalStore(subscribe, () => positionsFor(current, key));

  // Opening another analysis (or finding positions of an older layout version) forgets them.
  useEffect(() => {
    if (current && current.key !== key) {
      current = null;
      notify();
    }
  }, [key]);

  const move = useCallback(
    (moves: Positions) => {
      const base = positionsFor(current, key);
      const positions = applyMoves(base, moves);
      if (positions === base && current?.key === key) return;
      current = { key, positions };
      notify();
    },
    [key],
  );

  const reset = useCallback(() => {
    if (current?.key === key && Object.keys(current.positions).length === 0) return;
    current = null;
    notify();
  }, [key]);

  return { manual, move, reset };
}
