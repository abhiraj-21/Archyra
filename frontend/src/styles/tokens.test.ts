import fs from 'node:fs';
import { describe, expect, it } from 'vitest';
// @ts-expect-error: plain ESM helper shared with the scripts (the token file is read as text, never imported by the app)
import { TOKEN_FILE, resolvedTokens } from '../../scripts/lib/tokens.mjs';
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
