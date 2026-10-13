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

import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import vm from 'node:vm';
import { describe, it, expect } from 'vitest';

/**
 * The IIFE bundle (dist/atmosphere.global.js) is what <script> users load. It
 * sets window.atmosphere and friends only when a window exists, so loading it
 * in SSR, Node or a worker is a no-op rather than a ReferenceError (#2791).
 *
 * These tests run the built bundle, so they need `npm run build` first. CI
 * builds before testing; a local run without dist skips with a message, while
 * a CI run without dist fails instead of skipping silently.
 */

const bundlePath = fileURLToPath(new URL('../../dist/atmosphere.global.js', import.meta.url));
const mapPath = `${bundlePath}.map`;
const built = existsSync(bundlePath);

if (!built && process.env.CI) {
  throw new Error(`${bundlePath} is missing: CI must run \`npm run build\` before the tests`);
}
if (!built) {
  console.warn(`Skipping IIFE bundle tests: ${bundlePath} is missing, run \`npm run build\` first`);
}

/** A fresh realm holding the host globals the bundle may touch, but no window. */
function newContext(extra: Record<string, unknown> = {}): vm.Context {
  return vm.createContext({
    console,
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    queueMicrotask,
    URL,
    URLSearchParams,
    TextEncoder,
    TextDecoder,
    AbortController,
    ...extra,
  });
}

describe.skipIf(!built)('IIFE bundle dist/atmosphere.global.js', () => {
  const code = built ? readFileSync(bundlePath, 'utf8') : '';

  it('loads without a window and still defines AtmosphereJS', () => {
    const context = newContext();
    expect('window' in context).toBe(false);

    expect(() => vm.runInContext(code, context, { filename: bundlePath })).not.toThrow();

    expect(context.AtmosphereJS).toBeDefined();
    expect(typeof context.AtmosphereJS.Atmosphere).toBe('function');
    expect('window' in context).toBe(false);
  });

  it('sets every browser global when a window exists', () => {
    const window: Record<string, unknown> = {};
    const context = newContext({ window });

    vm.runInContext(code, context, { filename: bundlePath });

    const ns = context.AtmosphereJS;
    expect(window.atmosphere).toBeDefined();
    expect(window.atmosphere).toBe(ns.atmosphere);
    expect(window.Atmosphere).toBe(ns.Atmosphere);
    expect(window.AtmosphereRooms).toBe(ns.AtmosphereRooms);
    expect(window.subscribeStreaming).toBe(ns.subscribeStreaming);
    expect(typeof window.Atmosphere).toBe('function');
    expect(typeof window.AtmosphereRooms).toBe('function');
    expect(typeof window.subscribeStreaming).toBe('function');
  });

  it('ends with exactly one sourceMappingURL comment, after the globals', () => {
    const comments = code.match(/\/\/# sourceMappingURL=/g) ?? [];
    expect(comments).toHaveLength(1);

    const lines = code.split('\n').filter((line) => line.trim() !== '');
    expect(lines[lines.length - 1]).toBe('//# sourceMappingURL=atmosphere.global.js.map');
    expect(code.indexOf("if(typeof window!=='undefined')")).toBeGreaterThanOrEqual(0);
    expect(code.indexOf('window.atmosphere=')).toBeLessThan(code.indexOf('//# sourceMappingURL='));
  });

  it('ships a source map that covers no line past the bundle code', () => {
    expect(existsSync(mapPath)).toBe(true);
    const map = JSON.parse(readFileSync(mapPath, 'utf8')) as { mappings: string };

    // Mapped generated lines are separated by ';'. The footer and the comment
    // come after the code, so the map must not reach past the code lines.
    const codeLines = code.slice(0, code.indexOf('//# sourceMappingURL=')).split('\n').length;
    const mappedLines = map.mappings.split(';').length;
    expect(map.mappings.length).toBeGreaterThan(0);
    expect(mappedLines).toBeLessThanOrEqual(codeLines);
  });
});
