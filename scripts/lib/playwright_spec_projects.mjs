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

// Which Playwright projects run a test from each spec file?
//
//   node playwright_spec_projects.mjs <playwright.config.ts>
//
// Prints, for scripts/validate-playwright-project-coverage.sh:
//   project\t<name>                       one line per project the config declares
//   spec\t<path>\t<project,project,...>   one line per *.spec.ts under the config's
//                                         testDir; the list names the projects that
//                                         run at least one of its tests, and is empty
//                                         for a spec that runs no test at all
//
// The answer comes from Playwright itself: `playwright test --list
// --reporter=json`, resolved from the config's own node_modules. An earlier
// version re-implemented testMatch in this file and was wrong in exactly the way
// a re-implementation drifts: it ignored grep/grepInvert (per project and
// top-level), so a project whose filter left it zero tests, or a spec whose tests
// were all tagged @flaky, read as covered; it missed projects whose names were not
// single-quoted literals; and its TypeScript handling depended on the Node version.
// The runner's own listing has none of those gaps.
//
// The listing is taken under the environment of the per-push e2e legs
// (.github/workflows/e2e.yml "Run E2E Tests" on GitHub Actions: CI=true,
// LLM_MODE=fake, INCLUDE_FLAKY=false, no SMOKE_ONLY), never the caller's: the
// config turns INCLUDE_FLAKY=false into a top-level grepInvert of /@flaky/, and
// SMOKE_ONLY into a grep of /@smoke/ that only PR runs set. CI is set as the leg
// sets it: a spec that skips itself in CI (test.skip(!!process.env.CI, ...)) runs
// nothing there and must not read as covered. E2E_ALL_BROWSERS is forced on so the
// opt-in firefox/webkit projects are declared: the caller decides whether an opt-in
// project counts as running.
//
// A test the listing reports as statically skipped (expectedStatus 'skipped':
// test.skip, test.describe.skip, test.fixme, or quarantined() outside the
// quarantine lane) runs nothing and does not count. The specs that need a sample
// jar (they throw at load time in CI when it is missing, and skip outside CI)
// neither throw nor skip under E2E_LIST_ONLY, which this listing sets: --list runs
// no test, so the jar is not needed to know what CI would run.

import { readdirSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, relative, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const ME = 'playwright_spec_projects.mjs';

// The per-push e2e leg's selection environment (e2e.yml "Run E2E Tests").
const LEG_ENV = { CI: 'true', LLM_MODE: 'fake', INCLUDE_FLAKY: 'false', E2E_ALL_BROWSERS: 'true', E2E_LIST_ONLY: 'true' };
const UNSET = ['SMOKE_ONLY', 'RUN_QUARANTINED', 'PLAYWRIGHT_JSON_OUTPUT_NAME',
  'LLM_API_KEY', 'LLM_BASE_URL', 'LLM_MODEL', 'SPRING_AI_OPENAI_BASE_URL', 'SPRING_AI_OPENAI_API_KEY'];

function die(msg) {
  process.stderr.write(`${ME}: ${msg}\n`);
  process.exit(2);
}

const [configArg] = process.argv.slice(2);
if (!configArg) die('usage: playwright_spec_projects.mjs <playwright.config.ts>');
const configPath = resolve(configArg);
const configDir = dirname(configPath);

let cli;
try {
  cli = createRequire(configPath).resolve('@playwright/test/cli');
} catch {
  die(`@playwright/test is not installed for ${configPath} — run \`npm ci\` in ${configDir}`);
}

const env = { ...process.env };
for (const k of UNSET) delete env[k];
Object.assign(env, LEG_ENV);

const run = spawnSync(process.execPath, [cli, 'test', '--config', configPath, '--list', '--reporter=json'], {
  cwd: configDir,
  env,
  encoding: 'utf8',
  maxBuffer: 256 * 1024 * 1024,
});
if (run.error) die(`running playwright failed: ${run.error.message}`);

let report;
try {
  report = JSON.parse(run.stdout);
} catch {
  die(`playwright --list exited ${run.status} without a JSON report:\n${(run.stderr || run.stdout).trim()}`);
}
// A spec that fails to load is an error here, not a spec with zero tests.
const errors = report.errors ?? [];
if (errors.length > 0 || run.status !== 0) {
  const detail = errors.map((e) => `  ${(e.message ?? String(e)).split('\n')[0]}`).join('\n');
  die(`playwright --list exited ${run.status} with ${errors.length} error(s):\n${detail || run.stderr.trim()}`);
}

const projects = (report.config?.projects ?? []).map((p) => p.name);
if (projects.length === 0) die('config declares no projects');
// Playwright invents one unnamed project for a config without `projects`; no
// workflow can select it by name, so it is refused rather than reported as ''.
if (projects.some((n) => !n)) die('config declares a project with no name — no workflow can select it');
const rootDir = report.config?.rootDir;
if (!rootDir) die('playwright --list reported no rootDir');

// absolute spec path -> Set(project names that run at least one of its tests)
const runners = new Map();
function collect(suite, file) {
  for (const spec of suite.specs ?? []) {
    for (const t of spec.tests ?? []) {
      // A statically skipped test is listed, but runs nothing.
      if (t.expectedStatus === 'skipped') continue;
      if (!runners.has(file)) runners.set(file, new Set());
      runners.get(file).add(t.projectName);
    }
  }
  for (const child of suite.suites ?? []) collect(child, file);
}
for (const fileSuite of report.suites ?? []) collect(fileSuite, resolve(rootDir, fileSuite.file));

function walk(dir, out) {
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules') continue;
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, out);
    else if (entry.endsWith('.spec.ts')) out.push(full);
  }
  return out;
}
const specs = walk(rootDir, []).sort();
if (specs.length === 0) die(`no *.spec.ts under ${rootDir}`);

for (const name of projects) process.stdout.write(`project\t${name}\n`);
for (const file of specs) {
  const names = [...(runners.get(file) ?? [])].sort();
  process.stdout.write(`spec\t${relative(rootDir, file)}\t${names.join(',')}\n`);
}
