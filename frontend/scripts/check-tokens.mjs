#!/usr/bin/env node
// Fails when a colour literal or a font name appears anywhere in src/ outside the token file.
// Colours and fonts are Hallmark tokens (src/styles/tokens.css); everything else refers to them through var(--…)
// or the Tailwind utilities generated from them.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const srcDir = path.join(root, 'src');
const TOKEN_FILE = path.join(srcDir, 'styles', 'tokens.css');
const EXTENSIONS = new Set(['.ts', '.tsx', '.js', '.jsx', '.mjs', '.css', '.svg', '.html', '.json']);

const RULES = [
  { name: 'hex colour', re: /(?<![\w&/%:.-])#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})(?![\w-])/g },
  { name: 'colour function', re: /\b(?:rgba?|hsla?|hwb|lab|lch|oklab|oklch|color)\(/g },
  { name: 'font-family literal', re: /font-family\s*:\s*(?!\s*(?:var\(|inherit\b|initial\b|unset\b))[^;}]+/g },
  { name: 'fontFamily literal', re: /fontFamily\s*:\s*(?!\s*['"`]?var\()[^,}\n]+/g },
  { name: 'arbitrary font utility', re: /\bfont-\[[^\]]+\]/g },
  { name: 'arbitrary colour utility', re: /\b(?:bg|text|border|fill|stroke|outline|ring|decoration|shadow|from|via|to|accent|caret)-\[(?!var\()[^\]]+\]/g },
  {
    name: 'stock Tailwind palette',
    re: /\b(?:bg|text|border|fill|stroke|outline|ring|decoration|from|via|to|accent|caret|divide)-(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3}\b/g,
  },
  { name: 'absolute colour utility', re: /\b(?:bg|text|border|fill|stroke|outline|ring|decoration|divide)-(?:white|black)\b/g },
  { name: 'font name', re: /\b(?:General Sans|Geist(?: Mono)?|Inter|Roboto|Helvetica(?: Neue)?|Arial|Open Sans|Poppins|Lato|Menlo|Consolas|SF Mono|Courier(?: New)?)\b/g },
];

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (EXTENSIONS.has(path.extname(entry.name))) out.push(full);
  }
  return out;
}

function stripComments(text, ext) {
  // Comments may explain a rule ("no #fff here"); they are not styling. Line numbers are kept.
  const keepNewlines = (m) => m.replace(/[^\n]/g, ' ');
  let out = text.replace(/\/\*[\s\S]*?\*\//g, keepNewlines);
  if (['.ts', '.tsx', '.js', '.jsx', '.mjs'].includes(ext)) out = out.replace(/(^|[^:"'`\\])\/\/[^\n]*/g, (m, p) => p + ' '.repeat(m.length - p.length));
  if (['.svg', '.html'].includes(ext)) out = out.replace(/<!--[\s\S]*?-->/g, keepNewlines);
  return out;
}

const findings = [];
for (const file of walk(srcDir)) {
  if (file === TOKEN_FILE) continue;
  const ext = path.extname(file);
  const text = stripComments(fs.readFileSync(file, 'utf8'), ext);
  for (const rule of RULES) {
    for (const match of text.matchAll(rule.re)) {
      const line = text.slice(0, match.index).split('\n').length;
      findings.push(`${path.relative(root, file)}:${line}  ${rule.name}: ${match[0].trim().slice(0, 60)}`);
    }
  }
}

if (findings.length) {
  console.error(`check:tokens failed. Colours and fonts belong in ${path.relative(root, TOKEN_FILE)} only:\n`);
  for (const f of findings) console.error(`  ${f}`);
  process.exit(1);
}
console.log(`check:tokens passed: no colour or font literal in src/ outside ${path.relative(root, TOKEN_FILE)}.`);
