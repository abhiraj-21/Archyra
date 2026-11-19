import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
// @ts-expect-error: plain ESM helper shared with the scripts (the token file is read as text, never imported by the app)
import { DARK_SELECTOR, TOKEN_FILE, colourTokenNames, parity, readBlock, resolvedTokens } from '../../scripts/lib/tokens.mjs';
import snapshot from './tokens.light.snapshot.json';

const css = fs.readFileSync(TOKEN_FILE, 'utf8');
const light = resolvedTokens('light', css) as Record<string, string>;

describe('the light theme is unchanged', () => {
  const recorded = snapshot as Record<string, string>;

  it('still defines every token the snapshot recorded', () => {
    expect(Object.keys(recorded).filter((name) => !(name in light))).toEqual([]);
  });

  it('gives every recorded token exactly its recorded value', () => {
    // One entry per difference, so the failure names the token and both values.
    const changed = Object.keys(recorded)
      .filter((name) => light[name] !== recorded[name])
      .map((name) => `${name}: was ${recorded[name]} now ${light[name]}`);
    expect(changed).toEqual([]);
  });
});

const colourOf = (theme: 'light' | 'dark', token: string) => {
  const m = /oklch\(\s*([\d.]+)%\s+([\d.]+)\s+([\d.]+)/.exec(resolvedTokens(theme, css)[token] as string);
  if (!m) throw new Error(`${token} is not an oklch colour`);
  return { L: Number(m[1]), C: Number(m[2]), h: Number(m[3]) };
};

describe('the dark colourway', () => {
  it('defines every colour token the light theme defines, and nothing the light theme lacks (parity)', () => {
    const { missing, extra } = parity(css) as { missing: string[]; extra: string[] };
    expect(missing).toEqual([]);
    expect(extra).toEqual([]);
  });

  it('redefines colours only: fonts, spacing, radii, motion and layout are shared', () => {
    const colours = new Set(colourTokenNames(css) as string[]);
    const dark = [...(readBlock(DARK_SELECTOR, css).vars as Map<string, string>).keys()];
    expect(dark.filter((name) => !colours.has(name))).toEqual([]);
    for (const name of Object.keys(light).filter((n) => !colours.has(n))) {
      expect((resolvedTokens('dark', css) as Record<string, string>)[name]).toBe(light[name]);
    }
  });

  it('asks for the dark colour scheme, so native controls, scrollbars and autofill follow', () => {
    expect((readBlock(DARK_SELECTOR, css).props as Map<string, string>).get('color-scheme')).toBe('dark');
  });

  it('is a warm neutral dark: no blue-black, no pure black or white', () => {
    for (const token of ['--color-paper', '--color-paper-2', '--color-paper-3', '--color-cotton', '--color-desk', '--color-canvas', '--color-rule', '--color-rule-2', '--color-ink', '--color-ink-2', '--color-print', '--color-muted']) {
      const { L, C, h } = colourOf('dark', token);
      expect(L, `${token} lightness`).toBeGreaterThan(8);
      expect(L, `${token} lightness`).toBeLessThan(97);
      expect(C, `${token} chroma`).toBeLessThanOrEqual(0.012);
      expect(h, `${token} hue`).toBeGreaterThanOrEqual(35);
      expect(h, `${token} hue`).toBeLessThanOrEqual(65);
    }
  });

  it('puts raised surfaces above the page, and text well above both', () => {
    const L = (token: string) => colourOf('dark', token).L;
    expect(L('--color-cotton')).toBeGreaterThan(L('--color-paper'));
    expect(L('--color-desk')).toBeLessThan(L('--color-paper'));
    expect(L('--color-canvas')).toBeLessThan(L('--color-cotton'));
    expect(L('--color-ink')).toBeGreaterThanOrEqual(93);
    expect(L('--color-ink')).toBeLessThanOrEqual(96);
  });

  it('keeps coral as the accent (same hue family), and inverts the board so primary actions stay the strongest', () => {
    const hue = colourOf('dark', '--color-accent').h;
    expect(Math.abs(hue - colourOf('light', '--color-accent').h)).toBeLessThan(1);
    expect(colourOf('dark', '--color-board').L).toBeGreaterThan(colourOf('dark', '--color-paper').L + 50);
    expect(colourOf('dark', '--color-board-type').L).toBeLessThan(30);
  });

  it('turns both textures off', () => {
    const dark = resolvedTokens('dark', css) as Record<string, string>;
    expect(dark['--tex-cotton']).toBe('none');
    expect(dark['--tex-desk']).toBe('none');
  });
});

describe('components do not branch on the theme', () => {
  const root = path.resolve(__dirname, '..');
  function files(dir: string, ext: string[], out: string[] = []): string[] {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) files(full, ext, out);
      else if (ext.some((e) => entry.name.endsWith(e))) out.push(full);
    }
    return out;
  }

  it('has no [data-theme] selector outside tokens.css except the toggle icon swap', () => {
    const offenders: string[] = [];
    for (const file of files(root, ['.css'])) {
      if (file === TOKEN_FILE) continue;
      fs.readFileSync(file, 'utf8').split('\n').forEach((line, i) => {
        if (/\[data-theme/.test(line) && !/theme-icon/.test(line)) offenders.push(`${path.relative(root, file)}:${i + 1}  ${line.trim()}`);
      });
    }
    expect(offenders).toEqual([]);
  });
});
