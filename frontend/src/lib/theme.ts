/**
 * The colour theme: Light, Dark, or System (follows the device live). Pure helpers, no React.
 *
 * The choice is a harmless preference kept in localStorage under `archyra.theme`: not tied to the account, never sent to
 * the server. Every access is wrapped, because storage can be blocked (private windows, site settings) and must never
 * break the app: it then behaves as Light and the choice lasts only for the page's life.
 *
 * `public/theme-init.js` applies the same rules before first paint; `theme.test.ts` runs it against these.
 */

export type ThemePreference = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

export const THEME_STORAGE_KEY = 'archyra.theme';
/** Light is the default: nobody sees a different theme unless they choose one. */
export const DEFAULT_PREFERENCE: ThemePreference = 'light';
export const THEME_PREFERENCES: readonly ThemePreference[] = ['light', 'dark', 'system'];
export const THEME_LABELS: Record<ThemePreference, string> = { light: 'Light', dark: 'Dark', system: 'System' };

const DARK_QUERY = '(prefers-color-scheme: dark)';

type ReadableStorage = Pick<Storage, 'getItem'>;
type WritableStorage = Pick<Storage, 'setItem'>;

/** Anything that is not exactly one of the three values (null, junk, an old format) is the default. */
export function parsePreference(value: unknown): ThemePreference {
  return value === 'light' || value === 'dark' || value === 'system' ? value : DEFAULT_PREFERENCE;
}

function browserStorage(): Storage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null; // accessing localStorage itself can throw
  }
}

export function readPreference(storage: ReadableStorage | null = browserStorage()): ThemePreference {
  try {
    return parsePreference(storage?.getItem(THEME_STORAGE_KEY));
  } catch {
    return DEFAULT_PREFERENCE;
  }
}

/** Returns false when the choice could not be kept (it still applies for this page). */
export function writePreference(preference: ThemePreference, storage: WritableStorage | null = browserStorage()): boolean {
  try {
    if (!storage) return false;
    storage.setItem(THEME_STORAGE_KEY, preference);
    return true;
  } catch {
    return false;
  }
}

type MediaSource = Pick<Window, 'matchMedia'>;

export function systemPrefersDark(source: MediaSource | undefined = typeof window === 'undefined' ? undefined : window): boolean {
  try {
    return source?.matchMedia?.(DARK_QUERY).matches === true;
  } catch {
    return false;
  }
}

export function resolveTheme(preference: ThemePreference, systemDark: boolean): ResolvedTheme {
  if (preference === 'system') return systemDark ? 'dark' : 'light';
  return preference;
}

/** Calls `listener` whenever the device switches between light and dark. Returns the unsubscribe. */
export function subscribeSystemTheme(listener: (dark: boolean) => void, source: MediaSource | undefined = typeof window === 'undefined' ? undefined : window): () => void {
  let query: MediaQueryList | undefined;
  try {
    query = source?.matchMedia?.(DARK_QUERY);
  } catch {
    query = undefined;
  }
  if (!query) return () => undefined;
  const mql = query;
  const onChange = (event: MediaQueryListEvent) => listener(event.matches);
  mql.addEventListener('change', onChange);
  return () => mql.removeEventListener('change', onChange);
}

/** One pixel of the page colour as `#rrggbb`: the browser chrome (`theme-color`) cannot read a CSS variable. */
function pageColour(doc: Document): string | null {
  const win = doc.defaultView;
  if (!win || /jsdom/i.test(win.navigator.userAgent)) return null; // no canvas to convert with
  const value = win.getComputedStyle(doc.documentElement).getPropertyValue('--color-paper').trim();
  if (!value) return null;
  try {
    const canvas = doc.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext('2d', { willReadFrequently: true });
    if (!ctx) return null;
    ctx.fillStyle = value;
    ctx.fillRect(0, 0, 1, 1);
    const [r = 0, g = 0, b = 0] = ctx.getImageData(0, 0, 1, 1).data;
    return `#${[r, g, b].map((channel) => channel.toString(16).padStart(2, '0')).join('')}`;
  } catch {
    return null;
  }
}

/** Keeps `<meta name="theme-color">` equal to the page colour of the current theme (read from the token, not repeated here). */
export function syncThemeColor(doc: Document = document): void {
  const colour = pageColour(doc);
  if (!colour) return;
  let meta = doc.head.querySelector<HTMLMetaElement>('meta[name="theme-color"]');
  if (!meta) {
    meta = doc.createElement('meta');
    meta.name = 'theme-color';
    doc.head.appendChild(meta);
  }
  meta.content = colour;
}

/** Puts the theme on `<html>` (the one place CSS reads it) and updates the browser chrome colour. */
export function applyTheme(theme: ResolvedTheme, doc: Document = document): void {
  doc.documentElement.setAttribute('data-theme', theme);
  syncThemeColor(doc);
}
