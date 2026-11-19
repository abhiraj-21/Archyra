#!/usr/bin/env node
// npm run check:contrast: WCAG contrast of the colour pairs the UI actually draws, in BOTH themes.
//
// Reads the OKLCH tokens from src/styles/tokens.css (light :root, and the :root[data-theme="dark"] override on top of it),
// converts them to sRGB, prints one row per pair and theme, and fails under the thresholds:
//   4.5:1 for text (WCAG AA), 3:1 for non-text (lines, outlines, focus rings; WCAG 1.4.11).
// A pair that already failed in the LIGHT theme before the dark theme existed is listed in LIGHT_EXCEPTIONS below with
// the ratio it had: it is reported as "existing" and may not get worse. The light colours are never changed to fix one.
import fs from 'node:fs';
import { TOKEN_FILE, resolvedTokens } from './lib/tokens.mjs';

const TEXT = 4.5;
const NON_TEXT = 3;

const NODES = ['class', 'interface', 'method', 'function', 'module', 'file', 'repository'];
const EDGES = ['depends', 'calls', 'imports', 'extends', 'implements', 'references', 'contains'];
const SURFACES = ['paper', 'paper-2', 'cotton'];

/** [label, foreground token, background token, threshold]. Tokens are given without the --color- prefix. */
function pairs() {
  const out = [];
  for (const fg of ['ink', 'ink-2', 'muted']) for (const bg of SURFACES) out.push([`${fg} on ${bg}`, fg, bg, TEXT]);
  for (const bg of ['paper', 'cotton']) out.push([`accent-2 (links, selection) on ${bg}`, 'accent-2', bg, TEXT]);
  out.push(['board-type on board', 'board-type', 'board', TEXT]);
  out.push(['board-muted on board', 'board-muted', 'board', TEXT]);
  out.push(['accent-ink on accent', 'accent-ink', 'accent', TEXT]);
  out.push(['band-type on band (landing close)', 'band-type', 'band', TEXT]);
  for (const fg of ['ink', 'ink-2', 'muted']) out.push([`${fg} on paper-3 (selected row, citation)`, fg, 'paper-3', TEXT]);
  for (const s of ['error', 'success', 'warn']) {
    out.push([`${s} on ${s}-soft`, s, `${s}-soft`, TEXT]);
    out.push([`${s} on cotton`, s, 'cotton', TEXT]);
    out.push([`${s} on paper`, s, 'paper', TEXT]);
  }
  for (const n of NODES) {
    out.push([`node-${n} on node-${n}-soft`, `node-${n}`, `node-${n}-soft`, TEXT]);
    out.push([`node-${n} on cotton`, `node-${n}`, 'cotton', TEXT]);
  }
  for (const e of EDGES) out.push([`edge-${e} on canvas (line)`, `edge-${e}`, 'canvas', NON_TEXT]);
  for (const bg of ['paper', 'cotton']) out.push([`focus on ${bg} (ring)`, 'focus', bg, NON_TEXT]);
  out.push(['ink on canvas', 'ink', 'canvas', TEXT]);
  out.push(['muted on canvas', 'muted', 'canvas', TEXT]);
  out.push(['accent-2 on canvas (selection outline)', 'accent-2', 'canvas', NON_TEXT]);
  out.push(['ink-2 on canvas (dashed dependent card, badge)', 'ink-2', 'canvas', NON_TEXT]);
  return out;
}

/** Light pairs that failed before the dark theme was added (measured on the unchanged light values). They must not get worse. */
// Measured on the unchanged light tokens: every light pair passes, so there are none today. To record one, add
// `'<pair label>': <ratio it had>` (the label is the first column of the table).
const LIGHT_EXCEPTIONS = {};

// ---- colour maths ----
function parseOklch(value) {
  const m = /oklch\(\s*([\d.]+)(%?)\s+([\d.]+)\s+([\d.]+)(?:\s*\/\s*([\d.]+))?\s*\)/.exec(value);
  if (!m) throw new Error(`not an oklch() colour: ${value}`);
  return { L: m[2] ? Number(m[1]) / 100 : Number(m[1]), C: Number(m[3]), h: Number(m[4]), a: m[5] === undefined ? 1 : Number(m[5]) };
}
function toLinearSrgb({ L, C, h }) {
  const a = C * Math.cos((h * Math.PI) / 180);
  const b = C * Math.sin((h * Math.PI) / 180);
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  const clamp = (x) => Math.min(1, Math.max(0, x));
  return [
    clamp(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
    clamp(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
    clamp(-0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s),
  ];
}
const luminance = (rgb) => 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
export function contrast(fg, bg) {
  const a = luminance(toLinearSrgb(parseOklch(fg)));
  const b = luminance(toLinearSrgb(parseOklch(bg)));
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}

// ---- run ----
export function evaluate(cssText = fs.readFileSync(TOKEN_FILE, 'utf8')) {
  const rows = [];
  for (const theme of ['light', 'dark']) {
    const tokens = resolvedTokens(theme, cssText);
    for (const [label, fg, bg, min] of pairs()) {
      const f = tokens[`--color-${fg}`];
      const b = tokens[`--color-${bg}`];
      if (!f || !b) throw new Error(`missing token for "${label}" in ${theme}`);
      const ratio = contrast(f, b);
      const key = `${theme}: ${label}`;
      const existing = theme === 'light' ? LIGHT_EXCEPTIONS[label] : undefined;
      let status = 'pass';
      if (ratio + 1e-9 < min) {
        if (existing !== undefined && ratio + 0.01 >= existing) status = 'existing';
        else status = 'FAIL';
      }
      rows.push({ theme, label, key, ratio, min, status });
    }
  }
  return rows;
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1]}`).href) {
  const rows = evaluate();
  const width = Math.max(...rows.map((r) => r.label.length));
  const only = process.argv.includes('--failures');
  for (const theme of ['light', 'dark']) {
    console.log(`\n${theme.toUpperCase()}`);
    for (const r of rows.filter((x) => x.theme === theme)) {
      if (only && r.status === 'pass') continue;
      console.log(`  ${r.status.padEnd(8)} ${r.label.padEnd(width)}  ${r.ratio.toFixed(2).padStart(6)}:1  (min ${r.min})`);
    }
  }
  const failed = rows.filter((r) => r.status === 'FAIL');
  const existing = rows.filter((r) => r.status === 'existing');
  console.log(`\n${rows.length} checks, ${failed.length} failing, ${existing.length} existing light exceptions.`);
  if (failed.length) {
    console.error(`check:contrast failed:\n${failed.map((r) => `  ${r.key} is ${r.ratio.toFixed(2)}:1, needs ${r.min}:1`).join('\n')}`);
    process.exit(1);
  }
  console.log('check:contrast passed.');
}
