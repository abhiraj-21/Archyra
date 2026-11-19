#!/usr/bin/env node
// Writes src/styles/tokens.light.snapshot.json: every token of the LIGHT theme, resolved, as it is right now.
// The snapshot is the contract of the dark-theme work ("the light theme must not change"): src/styles/tokens.test.ts
// fails when any value in it differs. Re-run this ONLY when a light colour is meant to change.
import fs from 'node:fs';
import { SNAPSHOT_FILE, resolvedTokens } from './lib/tokens.mjs';

const tokens = resolvedTokens('light');
fs.writeFileSync(SNAPSHOT_FILE, `${JSON.stringify(tokens, null, 2)}\n`);
console.log(`wrote ${Object.keys(tokens).length} light tokens to ${SNAPSHOT_FILE}`);
