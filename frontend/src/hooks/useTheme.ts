import { useCallback, useLayoutEffect, useSyncExternalStore } from 'react';
import {
  DEFAULT_PREFERENCE,
  THEME_STORAGE_KEY,
  applyTheme,
  readPreference,
  resolveTheme,
  subscribeSystemTheme,
  systemPrefersDark,
  writePreference,
  type ResolvedTheme,
  type ThemePreference,
} from '@/lib/theme';

/**
 * The theme choice (Light, Dark or System) and the theme it resolves to right now. Every component that needs either
 * uses this hook; the root route mounts it once so that "System" keeps following the device on every screen.
 *
 * The preference lives in a tiny module-level store (not React state) so that every user of the hook, in every part of
 * the tree and in other tabs (the `storage` event), sees the same value without a provider.
 */
let preference: ThemePreference = readPreference();
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((listener) => listener());

function subscribePreference(listener: () => void): () => void {
  listeners.add(listener);
  const stored = readPreference(); // storage may have changed before anyone listened
  if (stored !== preference) preference = stored;
  const onStorage = (event: StorageEvent) => {
    if (event.key !== null && event.key !== THEME_STORAGE_KEY) return;
    const next = readPreference();
    if (next === preference) return;
    preference = next;
    emit();
  };
  window.addEventListener('storage', onStorage);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', onStorage);
  };
}

const subscribeSystem = (listener: () => void) => subscribeSystemTheme(() => listener());

function choose(next: ThemePreference): void {
  preference = next;
  writePreference(next); // a blocked store only means the choice is not remembered
  emit();
}

export interface ThemeState {
  /** What the person chose: light, dark, or system. */
  preference: ThemePreference;
  /** What is on screen: light or dark (system resolved against the device). */
  resolved: ResolvedTheme;
  setPreference: (next: ThemePreference) => void;
  /** The header button: switch to the other theme, as an explicit choice. */
  toggle: () => void;
}

export function useTheme(): ThemeState {
  const current = useSyncExternalStore(subscribePreference, () => preference, () => DEFAULT_PREFERENCE);
  const systemDark = useSyncExternalStore(subscribeSystem, () => systemPrefersDark(), () => false);
  const resolved = resolveTheme(current, systemDark);

  useLayoutEffect(() => {
    applyTheme(resolved);
  }, [resolved]);

  const toggle = useCallback(() => choose(resolved === 'dark' ? 'light' : 'dark'), [resolved]);
  return { preference: current, resolved, setPreference: choose, toggle };
}
