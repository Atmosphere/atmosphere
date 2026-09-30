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

// Which Playwright projects pick up each spec file?
//
//   node playwright_spec_projects.mjs <playwright.config.ts> [<spec-root>]
//
// Prints one line per *.spec.ts under <spec-root> (default: the config's
// testDir), as "<path relative to spec-root>\t<project,project,...>" — the
// project list is empty for a spec no project matches. Read by
// scripts/validate-playwright-project-coverage.sh.
//
// The config is EVALUATED, not regex-scraped: testMatch values are JavaScript
// regex literals, and re-parsing them from text is how a gate silently stops
// agreeing with the runner. It is evaluated without node_modules (the ci.yml
// gate job runs no `npm ci`) by stubbing the one module it imports,
// @playwright/test, with the two symbols it uses (defineConfig is an identity
// function; devices only feeds `use`, which this script ignores). Any other
// import is refused rather than guessed at.
//
// E2E_ALL_BROWSERS is forced on so the opt-in firefox/webkit projects are
// declared: the caller decides whether an opt-in project counts as running.
//
// Matching mirrors Playwright's own (playwright/lib/common/config.js +
// util.js createFileMatcher): per project, testDir / testMatch / testIgnore
// fall back to the top-level config, a RegExp is tested against the ABSOLUTE
// file path, and a file runs when it matches testMatch and not testIgnore.
// String globs need minimatch, which this dependency-free script does not
// carry, so any string pattern other than Playwright's default testMatch is
// refused with an error — a gate that cannot evaluate a pattern must not guess.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import vm from 'node:vm';
import * as nodeModule from 'node:module';

const ME = 'playwright_spec_projects.mjs';
const DEFAULT_TEST_MATCH = '**/*.@(spec|test).?(c|m)[jt]s?(x)';
// Exact equivalent of DEFAULT_TEST_MATCH under minimatch { nocase: true, dot: true }.
const DEFAULT_TEST_MATCH_RE = /\.(spec|test)\.(c|m)?[jt]sx?$/i;

function die(msg) {
  process.stderr.write(`${ME}: ${msg}\n`);
  process.exit(2);
}

const [configArg, specRootArg] = process.argv.slice(2);
if (!configArg) die('usage: playwright_spec_projects.mjs <playwright.config.ts> [<spec-root>]');
const configPath = resolve(configArg);
const configDir = dirname(configPath);

let source;
try {
  source = readFileSync(configPath, 'utf8');
} catch (e) {
  die(`cannot read ${configPath}: ${e.message}`);
}

if (typeof nodeModule.stripTypeScriptTypes === 'function') {
  // Experimental in Node 22/23: keep its one-line warning out of the gate's output.
  process.removeAllListeners('warning');
  source = nodeModule.stripTypeScriptTypes(source);
}

const importRe = /^\s*import\s+([^;]*?)\s+from\s+['"]([^'"]+)['"];?\s*$/gm;
for (const m of source.matchAll(importRe)) {
  if (m[2] !== '@playwright/test') die(`config imports '${m[2]}' — only @playwright/test is stubbed`);
}
source = source.replace(importRe, '');
if (/^\s*import\s/m.test(source)) die('config has an import this script cannot parse');
if (!/^export default\s/m.test(source)) die('config has no `export default` to evaluate');
source = source.replace(/^export default\s/m, '__config = ');

const sandbox = {
  __config: undefined,
  defineConfig: (c) => c,
  devices: new Proxy({}, { get: () => ({}) }),
  process: { env: { ...process.env, E2E_ALL_BROWSERS: 'true' } },
};
try {
  vm.runInNewContext(source, sandbox, { filename: configPath });
} catch (e) {
  die(`evaluating ${configPath} failed: ${e.message}`);
}
const config = sandbox.__config;
if (!config || typeof config !== 'object') die('config did not evaluate to an object');

// vm contexts have their own RegExp, so `instanceof RegExp` is false here.
const isRegExp = (v) => Object.prototype.toString.call(v) === '[object RegExp]';
const takeFirst = (...vs) => vs.find((v) => v !== undefined);

function matcher(patterns, what, project) {
  const list = Array.isArray(patterns) ? patterns : [patterns];
  const res = list.map((p) => {
    if (isRegExp(p)) return p;
    if (p === DEFAULT_TEST_MATCH) return DEFAULT_TEST_MATCH_RE;
    die(`project '${project}' has string ${what} '${p}' — only RegExp patterns (and the default testMatch) are evaluated`);
  });
  return (file) => res.some((re) => {
    re.lastIndex = 0;
    return re.test(file);
  });
}

const projects = (config.projects ?? []).map((p) => {
  const name = takeFirst(p.name, config.name, '');
  const testDir = resolve(configDir, takeFirst(p.testDir, config.testDir, '.'));
  return {
    name,
    testDir,
    match: matcher(takeFirst(p.testMatch, config.testMatch, DEFAULT_TEST_MATCH), 'testMatch', name),
    ignore: matcher(takeFirst(p.testIgnore, config.testIgnore, []), 'testIgnore', name),
  };
});
if (projects.length === 0) die('config declares no projects');

function walk(dir, out) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules') continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (entry.endsWith('.spec.ts')) out.push(full);
  }
  return out;
}

const specRoot = specRootArg ? resolve(specRootArg) : resolve(configDir, takeFirst(config.testDir, '.'));
const specs = walk(specRoot, []).sort();
if (specs.length === 0) die(`no *.spec.ts under ${specRoot}`);

for (const file of specs) {
  const names = projects
    .filter((p) => (file === p.testDir || file.startsWith(p.testDir + '/')) && p.match(file) && !p.ignore(file))
    .map((p) => p.name);
  process.stdout.write(`${relative(specRoot, file)}\t${names.join(',')}\n`);
}
