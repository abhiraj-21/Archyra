import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  THEME_STORAGE_KEY,
  applyTheme,
  parsePreference,
  readPreference,
  resolveTheme,
  subscribeSystemTheme,
  systemPrefersDark,
  writePreference,
  type ThemePreference,
} from './theme';

function memoryStorage(initial: Record<string, string> = {}) {
  const data = new Map(Object.entries(initial));
  return { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => void data.set(k, v), data };
}
const throwingStorage = {
  getItem: () => {
    throw new Error('blocked');
  },
  setItem: () => {
    throw new Error('blocked');
  },
};

/** A matchMedia that can be flipped like the device's setting. */
function fakeMedia(initialDark: boolean) {
  const listeners = new Set<(e: MediaQueryListEvent) => void>();
  let dark = initialDark;
  const mql = {
    get matches() {
      return dark;
    },
    addEventListener: (_: string, fn: (e: MediaQueryListEvent) => void) => listeners.add(fn),
    removeEventListener: (_: string, fn: (e: MediaQueryListEvent) => void) => listeners.delete(fn),
  };
  const source = { matchMedia: () => mql as unknown as MediaQueryList } as Pick<Window, 'matchMedia'>;
  return { source, listeners, set: (next: boolean) => { dark = next; listeners.forEach((fn) => fn({ matches: next } as MediaQueryListEvent)); } };
}

describe('parsePreference', () => {
  it('accepts exactly light, dark and system', () => {
    expect(parsePreference('light')).toBe('light');
    expect(parsePreference('dark')).toBe('dark');
    expect(parsePreference('system')).toBe('system');
  });
  it.each([null, undefined, '', 'Dark', ' dark', 'auto', 'blue', 1, {}, ['dark']])('falls back to light for %j', (value) => {
    expect(parsePreference(value)).toBe('light');
  });
});

describe('readPreference', () => {
  it('reads the stored value', () => {
    expect(readPreference(memoryStorage({ [THEME_STORAGE_KEY]: 'dark' }))).toBe('dark');
    expect(readPreference(memoryStorage({ [THEME_STORAGE_KEY]: 'system' }))).toBe('system');
  });
  it('is light when nothing is stored', () => {
    expect(readPreference(memoryStorage())).toBe('light');
  });
  it('is light when the stored value is not valid', () => {
    expect(readPreference(memoryStorage({ [THEME_STORAGE_KEY]: 'purple' }))).toBe('light');
  });
  it('is light, without throwing, when storage is missing or throws', () => {
    expect(readPreference(null)).toBe('light');
    expect(readPreference(throwingStorage)).toBe('light');
  });
  it('is light, without throwing, when even reading window.localStorage throws', () => {
    const spy = vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(readPreference()).toBe('light');
    expect(writePreference('dark')).toBe(false);
    spy.mockRestore();
  });
});

describe('writePreference', () => {
  it('stores the choice under archyra.theme', () => {
    const storage = memoryStorage();
    expect(writePreference('dark', storage)).toBe(true);
    expect(storage.data.get('archyra.theme')).toBe('dark');
  });
  it('says so, without throwing, when it cannot store', () => {
    expect(writePreference('dark', throwingStorage)).toBe(false);
    expect(writePreference('dark', null)).toBe(false);
  });
});

describe('resolveTheme', () => {
  it('uses the choice when it is light or dark, whatever the device says', () => {
    expect(resolveTheme('light', true)).toBe('light');
    expect(resolveTheme('dark', false)).toBe('dark');
  });
  it('follows the device for system', () => {
    expect(resolveTheme('system', true)).toBe('dark');
    expect(resolveTheme('system', false)).toBe('light');
  });
});

describe('the device setting', () => {
  it('is read from matchMedia, and is light when matchMedia is missing or throws', () => {
    expect(systemPrefersDark(fakeMedia(true).source)).toBe(true);
    expect(systemPrefersDark(fakeMedia(false).source)).toBe(false);
    expect(systemPrefersDark({} as Pick<Window, 'matchMedia'>)).toBe(false);
    expect(systemPrefersDark({ matchMedia: () => { throw new Error('no'); } } as unknown as Pick<Window, 'matchMedia'>)).toBe(false);
  });

  it('is followed live, and the subscription ends', () => {
    const media = fakeMedia(false);
    const seen: boolean[] = [];
    const stop = subscribeSystemTheme((dark) => seen.push(dark), media.source);
    media.set(true);
    media.set(false);
    expect(seen).toEqual([true, false]);
    stop();
    expect(media.listeners.size).toBe(0);
    media.set(true);
    expect(seen).toEqual([true, false]);
  });

  it('subscribing without matchMedia is a harmless no-op', () => {
    expect(() => subscribeSystemTheme(() => undefined, {} as Pick<Window, 'matchMedia'>)()).not.toThrow();
  });
});

describe('applyTheme', () => {
  afterEach(() => document.documentElement.removeAttribute('data-theme'));
  it('puts the theme on <html>', () => {
    applyTheme('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    applyTheme('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
  });
});

describe('public/theme-init.js (the pre-paint script) agrees with lib/theme.ts', () => {
  const source = fs.readFileSync(path.resolve(__dirname, '../../public/theme-init.js'), 'utf8');

  /** Runs the real script against a fake window and returns the data-theme it set. */
  function runInit({ stored, dark, storage = 'works', media = 'works' }: { stored?: string; dark: boolean; storage?: 'works' | 'throws'; media?: 'works' | 'missing' }) {
    let attribute: string | undefined;
    const fakeWindow = {
      localStorage: storage === 'throws' ? throwingStorage : { getItem: (k: string) => (k === 'archyra.theme' ? (stored ?? null) : null) },
      matchMedia: media === 'missing' ? undefined : () => ({ matches: dark }),
    };
    const fakeDocument = { documentElement: { setAttribute: (_: string, v: string) => { attribute = v; } } };
    new Function('window', 'document', source)(fakeWindow, fakeDocument);
    return attribute;
  }

  const stored: (string | undefined)[] = [undefined, 'light', 'dark', 'system', 'junk', ''];
  for (const value of stored) {
    for (const dark of [false, true]) {
      it(`stored ${JSON.stringify(value)}, device dark=${dark}`, () => {
        const expected = resolveTheme(readPreference(memoryStorage(value === undefined ? {} : { [THEME_STORAGE_KEY]: value })), dark);
        expect(runInit({ stored: value, dark })).toBe(expected);
      });
    }
  }

  it('is light when storage throws, and when matchMedia is missing for system', () => {
    expect(runInit({ dark: true, storage: 'throws' })).toBe('light');
    expect(runInit({ stored: 'system', dark: true, media: 'missing' })).toBe('light');
    const preference: ThemePreference = readPreference(throwingStorage);
    expect(resolveTheme(preference, false)).toBe('light');
  });

  it('is an external script with no colour in it', () => {
    expect(source).not.toMatch(/oklch|#[0-9a-f]{3,8}\b|rgb\(|hsl\(/i);
  });
});
