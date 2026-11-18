/// <reference lib="webworker" />
import { computeLayout, type LayoutInput } from './layoutCore';

// Runs the layout off the main thread so a large graph never freezes the page.
self.onmessage = (event: MessageEvent<{ id: number; input: LayoutInput }>) => {
  const { id, input } = event.data;
  try {
    (self as DedicatedWorkerGlobalScope).postMessage({ id, result: computeLayout(input) });
  } catch (error) {
    (self as DedicatedWorkerGlobalScope).postMessage({ id, error: error instanceof Error ? error.message : 'layout failed' });
  }
};
