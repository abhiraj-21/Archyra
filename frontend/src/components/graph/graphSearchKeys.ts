/** The one search field of the Architecture tab: `/` focuses it by this id. */
export const GRAPH_SEARCH_ID = 'graph-search';

/** The URL and the layout follow typing after this pause; the field itself updates at once. */
export const SEARCH_DEBOUNCE_MS = 250;

/** The longest filter term; the same limit the URL parser applies. */
export const MAX_SEARCH_LENGTH = 100;

/** True for a key press that happens while someone is typing somewhere, so `/` must not be taken from them. */
export function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName);
}
