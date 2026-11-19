/**
 * The version of the graph's orientation rules. Bump it whenever a change would make an old layout (or an old hand-placed
 * position) contradict what the graph now draws: the layout cache key and the manual-positions store key both carry it, so
 * nothing computed or dragged under an older orientation is ever reused.
 *
 * 1: a DEPENDS_ON edge was drawn dependent → dependency (the dependent on top).
 * 2: a DEPENDS_ON edge is drawn dependency → dependent (foundations on top); EXTENDS, IMPLEMENTS and REFERENCES rank the
 *    parent, interface or referenced type above the child (see `rankOrder` in adapter.ts).
 */
export const LAYOUT_VERSION = 2;

/** A layout-cache key made from the visible set's own key, tied to a layout version. */
export function layoutCacheKey(setKey: string, version: number = LAYOUT_VERSION): string {
  return `layout-v${version}|${setKey}`;
}
