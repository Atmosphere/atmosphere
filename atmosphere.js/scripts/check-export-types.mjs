#!/usr/bin/env node
/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

// Every file named by package.json `exports[*]` must exist after a build.
//
// This exists because `./react-native` shipped for multiple releases declaring
// "types": "./dist/react-native.d.ts" while that file was absent from the
// published tarball — the only entry of eleven missing its declarations. tsup
// wrote it, then the first config's `clean: true` raced the second config and
// deleted it. Nothing failed: the build exited 0, the tarball published, and
// consumers of the React Native entry silently got `any` instead of types,
// which is how a sample came to call `stats.totalTokens` (a field that does not
// exist) and render a hardcoded zero for months.
//
// A build that emits a package.json promise it cannot keep must fail loudly.

import { readFileSync, existsSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const pkg = JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8'));

const missing = [];
let checked = 0;

for (const [entry, conditions] of Object.entries(pkg.exports ?? {})) {
  if (typeof conditions !== 'object' || conditions === null) continue;
  for (const [condition, relative] of Object.entries(conditions)) {
    if (typeof relative !== 'string') continue;
    checked += 1;
    if (!existsSync(resolve(root, relative))) {
      missing.push(`${entry} → ${condition}: ${relative}`);
    }
  }
}

if (checked === 0) {
  console.error('check-export-types: no export targets found — the walk is broken, '
    + 'and this check would pass vacuously.');
  process.exit(1);
}

if (missing.length > 0) {
  console.error(
    `check-export-types: ${missing.length} of ${checked} export target(s) named by `
    + 'package.json do not exist in the build output:\n'
    + missing.map((m) => `  ${m}`).join('\n')
    + '\n\nThe package would publish declaring files it does not ship.',
  );
  process.exit(1);
}

console.log(`check-export-types: all ${checked} export targets present.`);
