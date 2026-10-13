/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// @vitest-environment node

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import tsupConfig from '../../tsup.config';

/**
 * The markdown renderers are used only by the chat UI (`atmosphere.js/chat`).
 * As plain `dependencies` they were installed — with their ~100 transitive
 * packages — for every consumer of the transport client. They are optional
 * peer dependencies, like the framework integrations, and stay external to
 * every bundle.
 */
const CHAT_ONLY = ['react-markdown', 'remark-gfm'];

const root = fileURLToPath(new URL('../..', import.meta.url));

const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')) as {
  dependencies?: Record<string, string>;
  devDependencies?: Record<string, string>;
  peerDependencies?: Record<string, string>;
  peerDependenciesMeta?: Record<string, { optional?: boolean }>;
};

function sources(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) return sources(full);
    return /\.tsx?$/.test(name) ? [full] : [];
  });
}

describe.each(CHAT_ONLY)('%s', (name) => {
  it('is not a runtime dependency', () => {
    expect(pkg.dependencies?.[name]).toBeUndefined();
  });

  it('is an optional peer dependency', () => {
    expect(pkg.peerDependencies?.[name]).toBeDefined();
    expect(pkg.peerDependenciesMeta?.[name]?.optional).toBe(true);
  });

  it('is a dev dependency, so the chat tests and build still resolve it', () => {
    expect(pkg.devDependencies?.[name]).toBeDefined();
  });

  it('is imported only by src/chat', () => {
    const importers = sources(join(root, 'src'))
      .filter((file) => new RegExp(`from\\s+['"]${name}['"]`).test(readFileSync(file, 'utf8')))
      .map((file) => relative(join(root, 'src'), file).split('\\').join('/'));
    expect(importers.length).toBeGreaterThan(0);
    expect(importers.filter((file) => !file.startsWith('chat/'))).toEqual([]);
  });

  it('is external to the build that bundles the chat entry', () => {
    const configs = Array.isArray(tsupConfig) ? tsupConfig : [tsupConfig];
    const chatBuild = configs.find(
      (config) => !Array.isArray(config.entry) && typeof config.entry === 'object' && 'chat' in config.entry,
    );
    expect(chatBuild?.external).toContain(name);
  });
});
