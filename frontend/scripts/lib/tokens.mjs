// Reads src/styles/tokens.css for the scripts and the token tests (never imported by the app).
// Understands exactly what the file contains: top-level rules, comments, and var(--x) references between tokens.
import fs from 'node:fs';
import path from 'node:path';
import { frontendRoot } from './env.mjs';

export const TOKEN_FILE = path.join(frontendRoot, 'src', 'styles', 'tokens.css');
export const SNAPSHOT_FILE = path.join(frontendRoot, 'src', 'styles', 'tokens.light.snapshot.json');
export const LIGHT_SELECTOR = ':root';
export const DARK_SELECTOR = ':root[data-theme="dark"]';

const stripComments = (css) => css.replace(/\/\*[\s\S]*?\*\//g, '');

/** Splits "a: b; c: d(e;f)" on top-level semicolons. */
function declarations(body) {
  const out = [];
  let depth = 0;
  let quote = '';
  let current = '';
  for (const ch of body) {
    if (quote) {
      if (ch === quote) quote = '';
    } else if (ch === '"' || ch === "'") quote = ch;
    else if (ch === '(') depth++;
    else if (ch === ')') depth--;
    if (ch === ';' && depth === 0 && !quote) {
      out.push(current);
      current = '';
    } else current += ch;
  }
  out.push(current);
  return out.map((d) => d.trim()).filter(Boolean);
}

/** Top-level rules as [selector, body]. Nested blocks (@media, @font-face bodies) are skipped. */
function topLevelRules(css) {
  const rules = [];
  let depth = 0;
  let start = 0;
  let selector = '';
  for (let i = 0; i < css.length; i++) {
    const ch = css[i];
    if (ch === '{') {
      if (depth === 0) {
        selector = css.slice(start, i).trim();
        start = i + 1;
      }
      depth++;
    } else if (ch === '}') {
      depth--;
      if (depth === 0) {
        rules.push([selector, css.slice(start, i)]);
        start = i + 1;
      }
    }
  }
  return rules;
}

/** The custom properties and other declarations of every top-level rule whose selector matches. */
export function readBlock(selector, cssText = fs.readFileSync(TOKEN_FILE, 'utf8')) {
  const vars = new Map();
  const props = new Map();
  for (const [sel, body] of topLevelRules(stripComments(cssText))) {
    if (sel.replace(/\s+/g, ' ') !== selector) continue;
    for (const d of declarations(body)) {
      const colon = d.indexOf(':');
      const name = d.slice(0, colon).trim();
      const value = d.slice(colon + 1).trim().replace(/\s+/g, ' ');
      (name.startsWith('--') ? vars : props).set(name, value);
    }
  }
  return { vars, props };
}

/** Substitutes var(--x) (with an optional fallback) until none is left. `scope` is the merged map the block sees. */
export function resolve(value, scope, trail = []) {
  return value.replace(/var\(\s*(--[\w-]+)\s*(?:,\s*([^)]*))?\)/g, (_m, name, fallback) => {
    if (trail.includes(name)) throw new Error(`token cycle: ${[...trail, name].join(' -> ')}`);
    if (scope.has(name)) return resolve(scope.get(name), scope, [...trail, name]);
    if (fallback !== undefined) return fallback.trim();
    throw new Error(`${name} is not defined`);
  });
}

/** Every token of one theme, fully resolved. Dark inherits what it does not override, as the cascade does. */
export function resolvedTokens(theme, cssText) {
  const light = readBlock(LIGHT_SELECTOR, cssText).vars;
  const scope = theme === 'dark' ? new Map([...light, ...readBlock(DARK_SELECTOR, cssText).vars]) : light;
  const out = {};
  for (const [name, value] of scope) out[name] = resolve(value, scope).replace(/\s+/g, ' ');
  return out;
}

/** A token is a "colour" token when its resolved value draws something: an oklch() colour, a raw colour triplet, a texture. */
export const COLOUR_CHANNELS = new Set(['--shade', '--lit', '--deep']);
export function colourTokenNames(cssText) {
  const light = resolvedTokens('light', cssText);
  return Object.keys(light).filter((n) => /oklch\(/.test(light[n]) || COLOUR_CHANNELS.has(n) || n.startsWith('--tex-'));
}

/** Names a dark block must define: every light colour token. Returns { missing, extra }. */
export function parity(cssText) {
  const required = colourTokenNames(cssText);
  const light = readBlock(LIGHT_SELECTOR, cssText).vars;
  const dark = readBlock(DARK_SELECTOR, cssText).vars;
  return {
    required,
    missing: required.filter((n) => !dark.has(n)),
    extra: [...dark.keys()].filter((n) => !light.has(n)),
  };
}

/** Compact form for comparisons with browser-computed values (which differ only in spacing). */
export const squash = (v) => v.replace(/\s+/g, '');
