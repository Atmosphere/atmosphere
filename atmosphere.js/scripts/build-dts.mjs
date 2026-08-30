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

// Declaration emit for the package, using TypeScript's own compiler.
//
// tsup's bundled dts paths (rollup-plugin-dts, vendored inside tsup/dist, and
// the api-extractor `experimentalDts` path) both call TypeScript internals that
// TS 7 removed, so neither can run here. Declarations therefore come straight
// from `tsc --emitDeclarationOnly`, which produces an unbundled tree mirroring
// src/. Three things stand between that tree and what package.json `exports`
// promises, and this script does all three:
//
//   1. The exports map names flat files (dist/react.d.ts), not a tree. Each of
//      the eleven entry points gets a one-line barrel re-exporting its entry
//      module out of the tree.
//   2. tsc writes relative specifiers verbatim, so the tree is full of
//      extensionless (`./base`) and directory (`../..`) imports. Those are
//      illegal in an ES-module-format declaration file under node16/nodenext
//      resolution, so every relative specifier is resolved against the emitted
//      tree and rewritten with an explicit extension.
//   3. The CJS half of the package needs .d.cts. A .d.cts may not import an
//      ESM-format file (TS1479), so the tree is emitted twice: dist/_types/
//      (.d.ts, `./x.js` specifiers) and dist/_types-cjs/ (.d.cts, `./x.cjs`
//      specifiers). Each half is internally consistent.

import { execFileSync } from 'node:child_process';
import {
  existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync,
} from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const staging = resolve(root, '.dts-staging');
const dist = resolve(root, 'dist');

// entry name in the exports map -> module path inside the emitted tree.
// Mirrors the `entry` maps of the two library configs in tsup.config.ts.
const ENTRIES = {
  index: 'index',
  react: 'hooks/react/index',
  vue: 'hooks/vue/index',
  svelte: 'hooks/svelte/index',
  chat: 'chat/index',
  room: 'room',
  streaming: 'streaming-entry',
  queue: 'queue',
  history: 'history',
  interactions: 'interactions',
  'react-native': 'hooks/react-native/index',
};

// A missing entry here would ship a package.json promise with no declarations
// behind it — the exact failure check-export-types.mjs was written for. Fail
// before emitting anything rather than after.
const declared = Object.keys(JSON.parse(readFileSync(resolve(root, 'package.json'), 'utf8')).exports)
  .map((e) => (e === '.' ? 'index' : e.replace(/^\.\//, '')));
const uncovered = declared.filter((e) => !(e in ENTRIES));
if (uncovered.length > 0) {
  console.error(`build-dts: package.json exports declare entries with no declaration source: ${uncovered.join(', ')}`);
  process.exit(1);
}

function walk(dir) {
  const out = [];
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else if (name.endsWith('.d.ts')) out.push(full);
  }
  return out;
}

// `from './x'`, `import('./x')`, `import './x'` — relative specifiers only.
const SPECIFIER = /(\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)(['"])(\.{1,2}(?:\/[^'"]*)?)\2/g;

/** Resolve a relative specifier against the emitted tree and give it an explicit extension. */
function retarget(fromFile, spec, ext, unresolved) {
  const base = resolve(dirname(fromFile), spec);
  if (existsSync(`${base}.d.ts`)) return `${spec}${ext}`;
  if (existsSync(join(base, 'index.d.ts'))) return `${spec.replace(/\/$/, '')}/index${ext}`;
  unresolved.push(`${relative(staging, fromFile)} -> ${spec}`);
  return spec;
}

function rewrite(source, file, ext, unresolved) {
  return source.replace(
    SPECIFIER,
    (match, head, quote, spec) => `${head}${quote}${retarget(file, spec, ext, unresolved)}${quote}`,
  );
}

/** Emit one copy of the tree with the given file extension and specifier extension. */
function emitTree(outDir, fileExt, specExt) {
  const unresolved = [];
  for (const file of walk(staging)) {
    const rel = relative(staging, file).replace(/\.d\.ts$/, fileExt);
    const target = join(outDir, rel);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, rewrite(readFileSync(file, 'utf8'), file, specExt, unresolved));
  }
  if (unresolved.length > 0) {
    console.error(
      `build-dts: ${unresolved.length} relative specifier(s) in the emitted declarations do not `
      + `resolve inside the tree — the declarations would not typecheck for a consumer:\n`
      + unresolved.map((u) => `  ${u}`).join('\n'),
    );
    process.exit(1);
  }
}

rmSync(staging, { recursive: true, force: true });
execFileSync(
  process.execPath,
  [resolve(root, 'node_modules/typescript/bin/tsc'), '-p', resolve(root, 'tsconfig.dts.json')],
  { cwd: root, stdio: 'inherit' },
);
if (!existsSync(staging)) {
  console.error('build-dts: tsc emitted no declarations.');
  process.exit(1);
}

mkdirSync(dist, { recursive: true });
rmSync(join(dist, '_types'), { recursive: true, force: true });
rmSync(join(dist, '_types-cjs'), { recursive: true, force: true });

emitTree(join(dist, '_types'), '.d.ts', '.js');
emitTree(join(dist, '_types-cjs'), '.d.cts', '.cjs');

// A .d.cts is only CommonJS-format if the nearest package.json says so, and the
// package root says "type": "module". Without this marker the .d.cts tree is
// read as ESM and its `./x.cjs` specifiers dangle.
writeFileSync(join(dist, '_types-cjs', 'package.json'), `${JSON.stringify({ type: 'commonjs' }, null, 2)}\n`);

const header = '// Generated by scripts/build-dts.mjs — do not edit.\n';
for (const [entry, modulePath] of Object.entries(ENTRIES)) {
  // The barrels are `export *`, which does not carry a default export. No entry
  // has one today; if one is added, say so instead of shipping a barrel that
  // silently drops it.
  const emitted = readFileSync(join(dist, '_types', `${modulePath}.d.ts`), 'utf8');
  if (/^\s*export\s+(default\b|\{[^}]*\bdefault\b)/m.test(emitted)) {
    console.error(
      `build-dts: entry '${entry}' has a default export, which the \`export *\` barrel would drop. `
      + 'Add an explicit `export { default }` line for it.',
    );
    process.exit(1);
  }
  writeFileSync(join(dist, `${entry}.d.ts`), `${header}export * from './_types/${modulePath}.js';\n`);
  writeFileSync(join(dist, `${entry}.d.cts`), `${header}export * from './_types-cjs/${modulePath}.cjs';\n`);
}

rmSync(staging, { recursive: true, force: true });
console.log(`build-dts: ${Object.keys(ENTRIES).length} entries — .d.ts + .d.cts barrels over dist/_types and dist/_types-cjs.`);
