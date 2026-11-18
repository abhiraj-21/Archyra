// Minimal .env reader for the capture and e2e scripts (never imported by the app). Process env wins over files;
// .env.local wins over .env. Values are never printed.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');

function parse(file) {
  const out = {};
  if (!fs.existsSync(file)) return out;
  for (const line of fs.readFileSync(file, 'utf8').split('\n')) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (!m) continue;
    let value = m[2];
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    out[m[1]] = value;
  }
  return out;
}

export function loadEnv() {
  return {
    ARCHYRA_APP_URL: 'http://localhost:5173',
    ARCHYRA_CAPTURE_REPO: 'https://github.com/spring-projects/spring-petclinic',
    ...parse(path.join(root, '.env')),
    ...parse(path.join(root, '.env.local')),
    ...Object.fromEntries(Object.entries(process.env).filter(([k]) => k.startsWith('ARCHYRA_') || k === 'VITE_BACKEND_ORIGIN')),
  };
}

export const frontendRoot = root;
